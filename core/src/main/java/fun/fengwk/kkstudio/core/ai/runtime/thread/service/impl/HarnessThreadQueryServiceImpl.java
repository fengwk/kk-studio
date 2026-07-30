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
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.usage.service.ModelUsageAggregationService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelUsageSummaryDTO;

import java.time.Clock;
import java.time.Instant;
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
  public List<HarnessThreadDTO> listAll() {
    Instant now = now();
    return queryMapper.listAllThreadViews().stream()
        .map(row -> converter.toThread(row, now))
        .toList();
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
