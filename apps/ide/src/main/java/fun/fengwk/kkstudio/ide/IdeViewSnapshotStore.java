package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Backend 唯一接受的 Daemon IDE 完整视图快照。
 *
 * <p>快照按 Daemon 进程身份隔离；同一进程只接受单调递增的 revision，更高 revision 原子替换，更低 revision 不生效，相同 revision
 * 必须内容完全一致。新 Daemon 进程使用新的缓存代际，不与旧进程比较 revision。迟到旧快照不能删除或覆盖新绑定。
 */
public final class IdeViewSnapshotStore {

  public static final int MAX_VIEWS = 64;

  /** 一条来自完整快照的 IDE 视图；所有字段都由 Daemon 提交点确认。 */
  public record View(
      UUID viewId,
      UUID browserSessionId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      long bindingEpoch,
      String directory,
      String workspacePath,
      IdeViewState state) {

    public View {
      viewId = Objects.requireNonNull(viewId, "viewId");
      browserSessionId = Objects.requireNonNull(browserSessionId, "browserSessionId");
      eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
      connectionId = Objects.requireNonNull(connectionId, "connectionId");
      claimId = Objects.requireNonNull(claimId, "claimId");
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
      directory = requirePath(directory, "directory");
      workspacePath = requirePath(workspacePath, "workspacePath");
      state = Objects.requireNonNull(state, "state");
      if (state != IdeViewState.OPEN && state != IdeViewState.CLOSING) {
        throw new IllegalArgumentException("state must be OPEN or CLOSING");
      }
    }

    public boolean matchesBinding(UUID expectedBrowserSessionId, long expectedBindingEpoch) {
      return browserSessionId.equals(expectedBrowserSessionId)
          && bindingEpoch == expectedBindingEpoch;
    }
  }

  /** 一个 Daemon 进程在某个 revision 的不可变完整快照。 */
  public record Snapshot(DaemonConnectionIdentity daemonIdentity, long revision, List<View> views) {

    public Snapshot {
      daemonIdentity = Objects.requireNonNull(daemonIdentity, "daemonIdentity");
      if (revision < 0) {
        throw new IllegalArgumentException("revision must be non-negative");
      }
      views = List.copyOf(Objects.requireNonNull(views, "views"));
    }

    public Optional<View> find(UUID viewId) {
      Objects.requireNonNull(viewId, "viewId");
      return views.stream().filter(view -> view.viewId().equals(viewId)).findFirst();
    }
  }

  public enum Status {
    ACCEPTED,
    UNCHANGED,
    STALE
  }

  /** 一次接受结果；previous 只在 {@link Status#ACCEPTED} 且替换旧快照时存在。 */
  public record Acceptance(Status status, Snapshot snapshot, Snapshot previous) {

    public Acceptance {
      status = Objects.requireNonNull(status, "status");
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public boolean applied() {
      return status == Status.ACCEPTED;
    }
  }

  private final IdeCurrentGeneration currentGenerations;
  private final Object lock = new Object();
  private final Map<DaemonConnectionIdentity, Snapshot> snapshots = new HashMap<>();

  public IdeViewSnapshotStore(IdeCurrentGeneration currentGenerations) {
    this.currentGenerations = Objects.requireNonNull(currentGenerations, "currentGenerations");
  }

  /**
   * 接受一个完整快照。
   *
   * @throws DomainException 快照来自已换代的 Daemon，或同一 revision 内容漂移
   */
  public Acceptance accept(DaemonConnectionIdentity identity, long revision, List<View> views) {
    Objects.requireNonNull(identity, "identity");
    Snapshot candidate = new Snapshot(identity, revision, canonical(views));
    synchronized (lock) {
      requireCurrentGeneration(identity);
      discardOtherGenerations(identity);
      Snapshot current = snapshots.get(identity);
      if (current == null) {
        snapshots.put(identity, candidate);
        return new Acceptance(Status.ACCEPTED, candidate, null);
      }
      if (revision < current.revision()) {
        return new Acceptance(Status.STALE, current, null);
      }
      if (revision == current.revision()) {
        if (!current.views().equals(candidate.views())) {
          throw DomainException.unavailable(
              "IDE_PROTOCOL_ERROR",
              "daemon returned different IDE snapshot content for the same revision");
        }
        return new Acceptance(Status.UNCHANGED, current, null);
      }
      snapshots.put(identity, candidate);
      return new Acceptance(Status.ACCEPTED, candidate, current);
    }
  }

  /** 指定 Daemon 进程已接受的快照；不会跨 daemonInstanceId 比较 revision。 */
  public Optional<Snapshot> snapshot(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    synchronized (lock) {
      return Optional.ofNullable(snapshots.get(identity));
    }
  }

  /** 本节点当前 READY 进程的快照；没有 READY 连接时为空。 */
  public Optional<Snapshot> current(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    return currentGenerations.current(environmentId).flatMap(this::snapshot);
  }

  private static List<View> canonical(List<View> views) {
    Objects.requireNonNull(views, "views");
    if (views.size() > MAX_VIEWS) {
      throw DomainException.unavailable(
          "IDE_PROTOCOL_ERROR", "daemon IDE snapshot exceeds " + MAX_VIEWS + " views");
    }
    List<View> ordered = new ArrayList<>(views.size());
    Set<UUID> viewIds = new HashSet<>();
    for (View view : views) {
      Objects.requireNonNull(view, "view");
      if (!viewIds.add(view.viewId())) {
        throw DomainException.unavailable(
            "IDE_PROTOCOL_ERROR", "daemon IDE snapshot repeats a viewId");
      }
      ordered.add(view);
    }
    ordered.sort(Comparator.comparing(view -> view.viewId().toString()));
    return List.copyOf(ordered);
  }

  private void requireCurrentGeneration(DaemonConnectionIdentity identity) {
    if (!currentGenerations.current(identity.environmentId()).map(identity::equals).orElse(false)) {
      throw DomainException.conflict(
          "STALE_BINDING", "IDE snapshot belongs to a replaced daemon instance");
    }
  }

  private void discardOtherGenerations(DaemonConnectionIdentity current) {
    EnvironmentId environmentId = current.environmentId();
    snapshots
        .keySet()
        .removeIf(
            identity ->
                identity.environmentId().equals(environmentId) && !identity.equals(current));
  }

  private static String requirePath(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
