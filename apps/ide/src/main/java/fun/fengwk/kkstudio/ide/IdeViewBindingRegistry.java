package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * owner 节点内的 IDE 视图绑定快照：Daemon 进程身份 + viewId → 当前生效的 document/事件连接/claim/epoch。
 *
 * <p>唯一键是 (Environment, daemonInstanceId, viewId)：另一个 Daemon 进程即使复用相同 view/epoch/workspace
 * 也不会命中或覆盖当前进程的绑定。它只是 owner 从 Daemon 回执与 {@code LIST_VIEWS} 重建的路由缓存，不是权威存储。每次写命令前仍须向事件节点确认 exact
 * intent；旧 epoch 的关闭/ 目录/终端请求不得作用于新绑定。
 */
public final class IdeViewBindingRegistry {

  /** 一条 Daemon 已确认的视图绑定。 */
  public record Binding(
      DaemonConnectionIdentity daemonIdentity,
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      long bindingEpoch) {

    public Binding {
      daemonIdentity = Objects.requireNonNull(daemonIdentity, "daemonIdentity");
      viewId = Objects.requireNonNull(viewId, "viewId");
      documentId = Objects.requireNonNull(documentId, "documentId");
      eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
      connectionId = Objects.requireNonNull(connectionId, "connectionId");
      claimId = Objects.requireNonNull(claimId, "claimId");
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
    }

    public EnvironmentId environmentId() {
      return daemonIdentity.environmentId();
    }

    public boolean matches(UUID expectedDocumentId, long expectedEpoch) {
      return documentId.equals(expectedDocumentId) && bindingEpoch == expectedEpoch;
    }
  }

  private final ConcurrentHashMap<Key, Binding> bindings = new ConcurrentHashMap<>();

  /** 记录/替换一条绑定；同进程同 viewId 的新 claim 使旧绑定失效，同 Environment 其它进程的残留绑定被清除。 */
  public void record(Binding binding) {
    Objects.requireNonNull(binding, "binding");
    discardOtherGenerations(binding.daemonIdentity());
    bindings.put(key(binding.daemonIdentity(), binding.viewId()), binding);
  }

  public Optional<Binding> find(DaemonConnectionIdentity daemonIdentity, UUID viewId) {
    Objects.requireNonNull(daemonIdentity, "daemonIdentity");
    Objects.requireNonNull(viewId, "viewId");
    return Optional.ofNullable(bindings.get(key(daemonIdentity, viewId)));
  }

  /** 指定 Daemon 进程身份的本地路由快照（诊断/与权威列表对账）。 */
  public List<Binding> list(DaemonConnectionIdentity daemonIdentity) {
    Objects.requireNonNull(daemonIdentity, "daemonIdentity");
    return bindings.entrySet().stream()
        .filter(entry -> entry.getKey().daemonIdentity().equals(daemonIdentity))
        .map(Map.Entry::getValue)
        .toList();
  }

  /** 只有同进程同 view 的 epoch 仍匹配才移除；旧进程的关闭不能删除新绑定。 */
  public boolean removeIfMatches(
      DaemonConnectionIdentity daemonIdentity, UUID viewId, UUID documentId, long bindingEpoch) {
    Key key = key(daemonIdentity, viewId);
    Binding current = bindings.get(key);
    if (current == null || !current.matches(documentId, bindingEpoch)) {
      return false;
    }
    return bindings.remove(key, current);
  }

  /**
   * 用权威 {@code LIST_VIEWS} 快照替换该 Daemon 进程的本地缓存，并清掉同 Environment 其它进程的残留绑定。
   *
   * <p>权威快照来自当前实例即可证明旧进程不再拥有路由，因此这里不保留旧代际条目。
   */
  public List<Binding> replace(DaemonConnectionIdentity daemonIdentity, List<Binding> current) {
    Objects.requireNonNull(daemonIdentity, "daemonIdentity");
    Objects.requireNonNull(current, "current");
    discardOtherGenerations(daemonIdentity);
    Set<UUID> currentViewIds =
        current.stream()
            .filter(binding -> binding.daemonIdentity().equals(daemonIdentity))
            .map(Binding::viewId)
            .collect(Collectors.toSet());
    bindings
        .entrySet()
        .removeIf(
            entry -> {
              Key key = entry.getKey();
              return key.daemonIdentity().equals(daemonIdentity)
                  && !currentViewIds.contains(key.viewId());
            });
    for (Binding binding : current) {
      if (binding.daemonIdentity().equals(daemonIdentity)) {
        bindings.put(key(daemonIdentity, binding.viewId()), binding);
      }
    }
    return current;
  }

  private void discardOtherGenerations(DaemonConnectionIdentity daemonIdentity) {
    EnvironmentId environmentId = daemonIdentity.environmentId();
    bindings
        .keySet()
        .removeIf(
            key ->
                key.daemonIdentity().environmentId().equals(environmentId)
                    && !key.daemonIdentity().equals(daemonIdentity));
  }

  private static Key key(DaemonConnectionIdentity daemonIdentity, UUID viewId) {
    return new Key(daemonIdentity, viewId);
  }

  private record Key(DaemonConnectionIdentity daemonIdentity, UUID viewId) {}
}
