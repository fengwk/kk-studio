package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * IDE 视图绑定的授权边界：READY 实例、事件节点已确认的 claim 意图，以及权威快照中的 owner 绑定。
 *
 * <p>任何写命令发出前都必须在这里通过三道检查：本节点当前 READY、事件节点精确确认同一 view/claim、快照中的 browserSession/epoch 仍是调用方的。检查失败一律
 * 失败关闭，绝不把“查不到”当成“可以新建”。
 */
final class IdeBindings {

  private final IdeCommandTransport transport;
  private final IdeBrowserUseGateway browserUse;
  private final IdeSnapshots snapshots;
  private final ConcurrentMap<UserBindingKey, UUID> userBindings = new ConcurrentHashMap<>();

  IdeBindings(
      IdeCommandTransport transport, IdeBrowserUseGateway browserUse, IdeSnapshots snapshots) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.browserUse = Objects.requireNonNull(browserUse, "browserUse");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
  }

  /** 本节点当前 READY 的 Daemon 进程身份；没有就绪连接时命令未发送。 */
  DaemonConnectionIdentity requireReady(EnvironmentId environmentId) {
    return transport
        .connectionIdentity(environmentId)
        .orElseThrow(
            () ->
                DomainException.unavailable("DAEMON_NOT_READY", "environment daemon is not ready"));
  }

  /** 事件节点必须确认同一 view/claim 的浏览器使用意图。 */
  void requireIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID browserSessionId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId,
      UUID userId) {
    boolean matched;
    try {
      matched =
          browserUse.findExactIntent(
              eventNodeId, connectionId, browserSessionId, environmentId, viewId, claimId, userId);
    } catch (DomainException error) {
      if ("IDE_UNAVAILABLE".equals(error.reason()) || "DAEMON_NOT_READY".equals(error.reason())) {
        throw error;
      }
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser IDE usage intent is unavailable", error);
    } catch (RuntimeException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser IDE usage intent is unavailable", error);
    }
    if (!matched) {
      throw IdeProtocol.staleBinding("browser IDE usage intent is no longer confirmed");
    }
  }

  /** 从权威快照取调用方声明的那条绑定；缓存缺失时先读一次 Daemon 快照。 */
  Binding requireBinding(
      EnvironmentId environmentId,
      UUID viewId,
      UUID browserSessionId,
      long bindingEpoch,
      String expectedDaemonInstanceId,
      UUID userId) {
    DaemonConnectionIdentity identity = requireReady(environmentId);
    requireExpectedInstance(identity, expectedDaemonInstanceId);
    IdeViewSnapshotStore.View view = snapshots.cachedView(identity, viewId).orElse(null);
    if (view == null) {
      view = snapshots.refresh(environmentId, identity).find(viewId).orElse(null);
    }
    if (view == null) {
      throw IdeProtocol.viewGone("IDE view is gone; open a new view before retrying");
    }
    if (!view.browserSessionId().equals(browserSessionId)) {
      throw IdeProtocol.viewOwnedByOther();
    }
    if (view.bindingEpoch() != bindingEpoch) {
      throw IdeProtocol.staleBinding("IDE view binding has been replaced");
    }
    requireIntent(
        view.eventNodeId(),
        view.connectionId(),
        browserSessionId,
        environmentId,
        viewId,
        view.claimId(),
        userId);
    return new Binding(identity, view);
  }

  private static void requireExpectedInstance(
      DaemonConnectionIdentity identity, String expectedDaemonInstanceId) {
    IdeInputs.requireCurrentInstance(
        expectedDaemonInstanceId, "expectedDaemonInstanceId is required", identity);
  }

  void requireUser(UUID userId, EnvironmentId environmentId, UUID viewId) {
    UUID boundUser = userBindings.get(new UserBindingKey(environmentId, viewId));
    if (boundUser == null || !boundUser.equals(userId)) {
      throw DomainException.notFoundReason("RESOURCE_NOT_FOUND", "Resource not found");
    }
  }

  void bindUser(UUID userId, EnvironmentId environmentId, UUID viewId) {
    UUID previous = userBindings.putIfAbsent(new UserBindingKey(environmentId, viewId), userId);
    if (previous != null && !previous.equals(userId)) {
      throw DomainException.notFoundReason("RESOURCE_NOT_FOUND", "Resource not found");
    }
  }

  UUID boundUser(EnvironmentId environmentId, UUID viewId) {
    return userBindings.get(new UserBindingKey(environmentId, viewId));
  }

  private record UserBindingKey(EnvironmentId environmentId, UUID viewId) {}

  /** 一条已确认的 owner 绑定：READY 实例与权威快照中的视图。 */
  record Binding(DaemonConnectionIdentity identity, IdeViewSnapshotStore.View view) {}
}
