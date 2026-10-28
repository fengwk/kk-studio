package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeViewState;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * owner 节点内的 IDE 视图绑定快照：viewId → 当前生效的 document/事件连接/claim/epoch。
 *
 * <p>它只是 owner 从 Daemon 回执与 {@code LIST_VIEWS} 重建的路由缓存，不是权威存储。每次写命令前仍须向事件节点确认 exact intent；旧 epoch
 * 的关闭/ 目录/终端请求不得作用于新绑定。
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
      long bindingEpoch,
      String directory,
      String workspacePath,
      IdeViewState state) {

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
      directory = Objects.requireNonNull(directory, "directory");
      workspacePath = Objects.requireNonNull(workspacePath, "workspacePath");
      state = Objects.requireNonNull(state, "state");
    }

    public EnvironmentId environmentId() {
      return daemonIdentity.environmentId();
    }

    public boolean matches(UUID expectedDocumentId, long expectedEpoch) {
      return documentId.equals(expectedDocumentId) && bindingEpoch == expectedEpoch;
    }

    public Binding withDirectory(String updatedDirectory) {
      return new Binding(
          daemonIdentity,
          viewId,
          documentId,
          eventNodeId,
          connectionId,
          claimId,
          bindingEpoch,
          updatedDirectory,
          workspacePath,
          state);
    }
  }

  private final ConcurrentHashMap<Key, Binding> bindings = new ConcurrentHashMap<>();

  /** 记录/替换一条绑定；同 viewId 的新 claim 使旧绑定失效。 */
  public void record(Binding binding) {
    Objects.requireNonNull(binding, "binding");
    bindings.put(new Key(binding.environmentId(), binding.viewId()), binding);
  }

  public Optional<Binding> find(EnvironmentId environmentId, UUID viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    return Optional.ofNullable(bindings.get(new Key(environmentId, viewId)));
  }

  /** 当前 Environment 的本地路由快照（诊断/与权威列表对账）。 */
  public List<Binding> listEnvironment(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    return bindings.entrySet().stream()
        .filter(entry -> entry.getKey().environmentId().equals(environmentId))
        .map(Map.Entry::getValue)
        .toList();
  }

  /** 只有 document+epoch 仍匹配时才更新目录，避免迟到回执覆盖新绑定。 */
  public boolean updateDirectory(
      EnvironmentId environmentId,
      UUID viewId,
      UUID documentId,
      long bindingEpoch,
      String directory) {
    Key key = new Key(environmentId, viewId);
    Binding current = bindings.get(key);
    if (current == null || !current.matches(documentId, bindingEpoch)) {
      return false;
    }
    Binding updated = current.withDirectory(directory);
    return bindings.replace(key, current, updated);
  }

  /** 只有 epoch 仍匹配才移除；旧关闭不能删除新绑定。 */
  public boolean removeIfMatches(
      EnvironmentId environmentId, UUID viewId, UUID documentId, long bindingEpoch) {
    Key key = new Key(environmentId, viewId);
    Binding current = bindings.get(key);
    if (current == null || !current.matches(documentId, bindingEpoch)) {
      return false;
    }
    return bindings.remove(key, current);
  }

  /** 某个 Daemon 代际结束时清除其全部路由；同一实例断线重连不清。 */
  public void clearGeneration(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    bindings.entrySet().removeIf(entry -> entry.getValue().daemonIdentity().equals(identity));
  }

  /** 用权威 LIST_VIEWS 快照替换该 Environment 的本地缓存，并返回已记录的绑定。 */
  public List<Binding> replaceEnvironment(EnvironmentId environmentId, List<Binding> current) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(current, "current");
    Set<UUID> currentViewIds = current.stream().map(Binding::viewId).collect(Collectors.toSet());
    bindings
        .entrySet()
        .removeIf(
            entry -> {
              Key key = entry.getKey();
              return key.environmentId().equals(environmentId)
                  && !currentViewIds.contains(key.viewId());
            });
    for (Binding binding : current) {
      if (binding.environmentId().equals(environmentId)) {
        bindings.put(new Key(environmentId, binding.viewId()), binding);
      }
    }
    return current;
  }

  private record Key(EnvironmentId environmentId, UUID viewId) {}
}
