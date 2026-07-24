package fun.fengwk.kkstudio.core.harness.task.store;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.harness.query.DerivedThreadStatus;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivityStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Root-tree activity projection from final session tree + derived Thread status。
 *
 * <p>不再读 {@code harness_thread_event}；eventId 使用 thread id 作为稳定 cursor。
 */
@Repository
public class DatabaseRootActivityStore implements RootActivityStore {
  private final PostgresqlHarnessQueryMapper queryMapper;
  private final Clock clock;

  public DatabaseRootActivityStore(PostgresqlHarnessQueryMapper queryMapper, Clock clock) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<RootActivity> list(long rootSessionId, long afterEventId, int limit) {
    if (rootSessionId <= 0) {
      throw new IllegalArgumentException("rootSessionId must be positive");
    }
    if (afterEventId < 0 || limit <= 0) {
      throw new IllegalArgumentException("afterEventId and limit must be valid");
    }
    HarnessQueryRow seed = queryMapper.findSession(rootSessionId);
    if (seed == null) {
      throw new IllegalArgumentException("unknown session: " + rootSessionId);
    }
    long resolvedRoot = resolveRoot(seed);
    Instant now = clock.instant();
    List<RootActivity> activities = new ArrayList<>();
    for (HarnessQueryRow session : queryMapper.listSessionTree(resolvedRoot)) {
      for (HarnessQueryRow thread : queryMapper.listThreadViewsBySession(session.getId())) {
        if (thread.getId() <= afterEventId) {
          continue;
        }
        String status = DerivedThreadStatus.derive(thread, now);
        String title = thread.getSessionTitle() == null ? "" : thread.getSessionTitle();
        activities.add(
            new RootActivity(
                resolvedRoot,
                session.getId(),
                thread.getId(),
                thread.getId(),
                toEventType(status),
                "{\"status\":\"" + status + "\",\"title\":\"" + escape(title) + "\"}",
                thread.getUpdatedAt() == null
                    ? thread.getCreatedAt().toInstant()
                    : thread.getUpdatedAt().toInstant()));
      }
    }
    return activities.stream()
        .sorted(Comparator.comparingLong(RootActivity::eventId))
        .limit(limit)
        .toList();
  }

  private long resolveRoot(HarnessQueryRow session) {
    long current = session.getId();
    Long parent = session.getParentSessionId();
    int guard = 0;
    while (parent != null) {
      HarnessQueryRow parentRow = queryMapper.findSession(parent);
      if (parentRow == null) {
        break;
      }
      current = parentRow.getId();
      parent = parentRow.getParentSessionId();
      if (++guard > 10_000) {
        throw new IllegalStateException("session parent chain too deep: " + session.getId());
      }
    }
    return current;
  }

  private static ThreadEventType toEventType(String status) {
    return switch (status) {
      case DerivedThreadStatus.RUNNING, DerivedThreadStatus.RUNNABLE -> ThreadEventType
          .THREAD_RUNNING;
      case DerivedThreadStatus.WAITING -> ThreadEventType.THREAD_WAITING;
      default -> ThreadEventType.THREAD_IDLE;
    };
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
