package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;

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

  public HarnessThreadQueryServiceImpl(
      PostgresqlHarnessQueryMapper queryMapper, HarnessQueryDtoConverter converter, Clock clock) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.clock = Objects.requireNonNull(clock, "clock");
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

  @Override
  public List<HarnessThreadDTO> listBySession(String sessionId) {
    long id = HarnessIds.parsePositive(sessionId, "sessionId");
    if (queryMapper.findSession(id) == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    Instant now = now();
    return queryMapper.listThreadViewsBySession(id).stream()
        .map(row -> converter.toThread(row, now))
        .toList();
  }

  @Override
  public List<HarnessSessionEntryDTO> listPathEntries(String threadId) {
    HarnessQueryRow thread = requireThread(threadId);
    return queryMapper.loadPath(thread.getSessionId(), thread.getHeadEntryId()).stream()
        .map(converter::toEntry)
        .toList();
  }

  @Override
  public List<HarnessThreadInputDTO> listInputs(String threadId) {
    HarnessQueryRow thread = requireThread(threadId);
    return queryMapper.listInputsByThread(thread.getId()).stream().map(converter::toInput).toList();
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
