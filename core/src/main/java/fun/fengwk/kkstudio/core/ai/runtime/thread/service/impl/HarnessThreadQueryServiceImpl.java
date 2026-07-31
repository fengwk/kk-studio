package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.ai.runtime.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.ai.runtime.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.ai.runtime.thread.query.ThreadCursor;
import fun.fengwk.kkstudio.core.ai.runtime.thread.query.ThreadCursorCodec;
import fun.fengwk.kkstudio.core.ai.runtime.thread.query.ThreadListQuery;
import fun.fengwk.kkstudio.core.ai.runtime.thread.query.ThreadSort;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.usage.service.ModelUsageAggregationService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelUsageSummaryDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** final PostgreSQL Thread snapshot read model。 */
@Service
public class HarnessThreadQueryServiceImpl implements HarnessThreadQueryService {
  private final PostgresqlHarnessQueryMapper queryMapper;
  private final HarnessQueryDtoConverter converter;
  private final Clock clock;
  private final HarnessObservabilityQueryService observabilityQueryService;
  private final ModelUsageAggregationService usageAggregationService;

  public HarnessThreadQueryServiceImpl(
      PostgresqlHarnessQueryMapper queryMapper, HarnessQueryDtoConverter converter, Clock clock) {
    this(queryMapper, converter, clock, null, null);
  }

  /** Application constructor includes the facts folded into the unified Thread projection. */
  @Autowired
  public HarnessThreadQueryServiceImpl(
      PostgresqlHarnessQueryMapper queryMapper,
      HarnessQueryDtoConverter converter,
      Clock clock,
      HarnessObservabilityQueryService observabilityQueryService,
      ModelUsageAggregationService usageAggregationService) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.observabilityQueryService = observabilityQueryService;
    this.usageAggregationService = usageAggregationService;
  }

  @Override
  public HarnessThreadDTO getThread(String threadId) {
    return converter.toThread(requireThread(threadId), now());
  }

  @Override
  public CursorPageDTO<HarnessThreadDTO> listAll(String sort, String cursor, Integer limit) {
    return list(null, sort, cursor, limit);
  }

  @Override
  public CursorPageDTO<HarnessThreadDTO> listByChat(
      long chatId, String sort, String cursor, Integer limit) {
    if (chatId <= 0) {
      throw new IllegalArgumentException("chatId must be positive");
    }
    return list(chatId, sort, cursor, limit);
  }

  private CursorPageDTO<HarnessThreadDTO> list(
      Long chatId, String sort, String cursor, Integer limit) {
    ThreadListQuery pageQuery = ThreadListQuery.parse(sort, cursor, limit);
    OffsetDateTime cursorTime =
        pageQuery.cursor() == null
            ? null
            : OffsetDateTime.ofInstant(pageQuery.cursor().time(), ZoneOffset.UTC);
    Instant now = now();
    List<HarnessQueryRow> rows =
        queryMapper.listThreadViews(
            chatId,
            pageQuery.sort().wireValue(),
            cursorTime,
            pageQuery.cursor() == null ? null : pageQuery.cursor().threadId(),
            pageQuery.limit() + 1);
    boolean hasNext = rows.size() > pageQuery.limit();
    List<HarnessQueryRow> pageRows = hasNext ? rows.subList(0, pageQuery.limit()) : rows;
    CursorPageDTO<HarnessThreadDTO> page = new CursorPageDTO<>();
    page.setItems(pageRows.stream().map(row -> converter.toThread(row, now)).toList());
    page.setNextCursor(
        hasNext
            ? ThreadCursorCodec.encode(
                new ThreadCursor(
                    pageQuery.sort(),
                    (pageQuery.sort() == ThreadSort.CREATED
                            ? pageRows.get(pageRows.size() - 1).getCreatedAt()
                            : pageRows.get(pageRows.size() - 1).getUpdatedAt())
                        .toInstant(),
                    pageRows.get(pageRows.size() - 1).getId()))
            : null);
    return page;
  }

  /**
   * Reads all user-visible Thread facts under PostgreSQL REPEATABLE READ. The revision in this
   * result is the durable invalidation cursor; callers must never infer state from LISTEN payloads.
   */
  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public HarnessThreadSnapshotDTO getSnapshot(String threadId) {
    if (observabilityQueryService == null || usageAggregationService == null) {
      throw new IllegalStateException("snapshot collaborators are not configured");
    }
    HarnessQueryRow row = requireThread(threadId);
    HarnessThreadSnapshotDTO snapshot = new HarnessThreadSnapshotDTO();
    snapshot.setRevision(Long.toString(row.getRevision()));
    snapshot.setThread(converter.toThread(row, now()));
    snapshot.setEntries(
        row.getHeadEntryId() == null
            ? List.of()
            : queryMapper.loadPath(row.getSessionId(), row.getHeadEntryId()).stream()
                .map(converter::toEntry)
                .toList());
    snapshot.setInputs(
        queryMapper.listInputsByThread(row.getId()).stream().map(converter::toInput).toList());
    snapshot.setModelInvocations(observabilityQueryService.listModelInvocations(threadId));
    snapshot.setToolInvocations(observabilityQueryService.listToolInvocations(threadId));
    snapshot.setOpenInteractions(observabilityQueryService.listOpenInteractions(threadId));
    snapshot.setUsage(
        row.getHeadEntryId() == null
            ? emptyThreadUsage(row.getId())
            : usageAggregationService.summarizeThread(row.getId()));
    return snapshot;
  }

  private static ModelUsageSummaryDTO emptyThreadUsage(long threadId) {
    ModelUsageSummaryDTO usage = new ModelUsageSummaryDTO();
    usage.setScopeType("thread");
    usage.setScopeId(Long.toString(threadId));
    return usage;
  }

  private HarnessQueryRow requireThread(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    HarnessQueryRow row = queryMapper.findThreadView(id);
    if (row == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    return row;
  }

  private Instant now() {
    return clock.instant();
  }
}
