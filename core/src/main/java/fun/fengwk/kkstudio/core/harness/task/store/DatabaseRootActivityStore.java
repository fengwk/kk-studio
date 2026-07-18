package fun.fengwk.kkstudio.core.harness.task.store;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivityStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Root-tree activity projection from ThreadEvent journal across all sessions under a root session.
 */
@Repository
public class DatabaseRootActivityStore implements RootActivityStore {
  private final HarnessSessionMapper sessionMapper;
  private final HarnessThreadMapper threadMapper;
  private final HarnessThreadEventMapper eventMapper;

  public DatabaseRootActivityStore(
      HarnessSessionMapper sessionMapper,
      HarnessThreadMapper threadMapper,
      HarnessThreadEventMapper eventMapper) {
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
  }

  @Override
  public List<RootActivity> list(long rootSessionId, long afterEventId, int limit) {
    if (rootSessionId <= 0) {
      throw new IllegalArgumentException("rootSessionId must be positive");
    }
    if (afterEventId < 0 || limit <= 0) {
      throw new IllegalArgumentException("afterEventId and limit must be valid");
    }
    HarnessSessionDO root = sessionMapper.find(rootSessionId);
    if (root == null) {
      throw new IllegalArgumentException("unknown session: " + rootSessionId);
    }
    long resolvedRoot = root.getRootSessionId() == null ? root.getId() : root.getRootSessionId();

    List<RootActivity> activities = new ArrayList<>();
    for (HarnessSessionDO session : listSessionsUnderRoot(resolvedRoot)) {
      for (HarnessThreadDO thread : threadMapper.listBySession(session.getId())) {
        List<HarnessThreadEventDO> events =
            eventMapper.listAfter(thread.getId(), afterEventId, Math.max(limit * 4, 100));
        for (HarnessThreadEventDO event : events) {
          activities.add(
              new RootActivity(
                  resolvedRoot,
                  session.getId(),
                  thread.getId(),
                  event.getId(),
                  ThreadEventType.fromValue(event.getEventType()),
                  event.getPayloadJson(),
                  event.getCreateTime().toInstant(ZoneOffset.UTC)));
        }
      }
    }
    return activities.stream()
        .filter(a -> a.eventId() > afterEventId)
        .sorted(Comparator.comparingLong(RootActivity::eventId))
        .limit(limit)
        .toList();
  }

  private List<HarnessSessionDO> listSessionsUnderRoot(long rootSessionId) {
    List<HarnessSessionDO> out = new ArrayList<>();
    HarnessSessionDO root = sessionMapper.find(rootSessionId);
    if (root != null) {
      out.add(root);
    }
    out.addAll(sessionMapper.listByRoot(rootSessionId));
    return out;
  }
}
