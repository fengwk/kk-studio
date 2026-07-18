package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadViewDO;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.util.List;
import java.util.Objects;

@Service
public class HarnessThreadQueryServiceImpl implements HarnessThreadQueryService {
  private final HarnessThreadMapper threadMapper;
  private final HarnessThreadInputMapper inputMapper;
  private final HarnessThreadEventMapper eventMapper;
  private final MysqlHarnessSessionStore sessionStore;
  private final HarnessSessionEntryMapper entryMapper;
  private final HarnessThreadDtoConverter converter;

  public HarnessThreadQueryServiceImpl(
      HarnessThreadMapper threadMapper,
      HarnessThreadInputMapper inputMapper,
      HarnessThreadEventMapper eventMapper,
      MysqlHarnessSessionStore sessionStore,
      HarnessSessionEntryMapper entryMapper,
      HarnessThreadDtoConverter converter) {
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  public HarnessThreadDTO getThread(String threadId) {
    return converter.convert(requireThread(threadId));
  }

  @Override
  public List<HarnessThreadDTO> listAll() {
    return threadMapper.listAllNewestFirst().stream().map(converter::convert).toList();
  }

  @Override
  public List<HarnessThreadDTO> listBySession(String sessionId) {
    long id = HarnessIds.parsePositive(sessionId, "sessionId");
    sessionStore
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
    return threadMapper.listViewsBySession(id).stream().map(converter::convert).toList();
  }

  @Override
  public List<HarnessSessionEntryDTO> listPathEntries(String threadId) {
    HarnessThreadViewDO row = requireThread(threadId);
    List<SessionEntry> path = sessionStore.loadPath(row.getSessionId(), row.getHeadEntryId());
    return path.stream()
        .map(
            entry -> {
              var entryRow = entryMapper.find(entry.sessionId(), entry.id());
              return converter.convert(entryRow);
            })
        .toList();
  }

  @Override
  public List<HarnessThreadInputDTO> listInputs(String threadId) {
    HarnessThreadViewDO row = requireThread(threadId);
    return inputMapper.listByThread(row.getId()).stream().map(converter::convert).toList();
  }

  @Override
  public List<ThreadEventDTO> listEvents(String threadId, long afterEventId, int limit) {
    HarnessThreadViewDO row = requireThread(threadId);
    ObservabilityLimits.requireNonNegativeCursor(afterEventId, "afterEventId");
    int page = ObservabilityLimits.normalizeLimit(limit, ObservabilityLimits.DEFAULT_LIMIT);
    return eventMapper.listAfter(row.getId(), afterEventId, page).stream()
        .map(converter::convert)
        .toList();
  }

  private HarnessThreadViewDO requireThread(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    HarnessThreadViewDO row = threadMapper.findView(id);
    if (row == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    return row;
  }
}
