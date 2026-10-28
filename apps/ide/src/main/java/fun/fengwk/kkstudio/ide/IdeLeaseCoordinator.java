package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 owner 上把 Daemon 的一轮 {@code ideLeaseRound} 翻译为一次 {@code IDE_LEASE}。
 *
 * <p>流程固定为：{@code LIST_VIEWS} 读取 Daemon 当前绑定 → 按 eventNode/connection/document 分组查询事件节点已确认的使用列表 →
 * 只保留 viewId/documentId/bindingEpoch 完全匹配的条目。事件节点查询失败、快照缺失或控制代际已换代都不续租；Daemon 心跳本身永远不被当作浏览器活跃证明。
 *
 * <p>每个 Environment 至多一个在途轮次；同一轮不会重放，下一轮由下一次正常心跳触发。回调只投递到有界执行器，不阻塞会话核心。
 */
public final class IdeLeaseCoordinator {

  private final EnvironmentIdeService localEnvironmentIdeService;
  private final IdeCommandTransport transport;
  private final IdeBrowserUseGateway browserUse;
  private final Executor executor;
  private final Map<EnvironmentId, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

  public IdeLeaseCoordinator(
      EnvironmentIdeService localEnvironmentIdeService,
      IdeCommandTransport transport,
      IdeBrowserUseGateway browserUse,
      Executor executor) {
    this.localEnvironmentIdeService =
        Objects.requireNonNull(localEnvironmentIdeService, "localEnvironmentIdeService");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.browserUse = Objects.requireNonNull(browserUse, "browserUse");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /** 会话核心在心跳状态推进后投递；{@code null} 轮次表示没有视图，不需要查询或续租。 */
  public void onHeartbeat(
      EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(identity, "identity");
    if (ideLeaseRound == null) {
      return;
    }
    AtomicBoolean active = inFlight.computeIfAbsent(environmentId, ignored -> new AtomicBoolean());
    if (!active.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              renew(environmentId, identity, ideLeaseRound);
            } finally {
              active.set(false);
            }
          });
    } catch (RejectedExecutionException error) {
      active.set(false);
    }
  }

  private void renew(
      EnvironmentId environmentId, DaemonConnectionIdentity identity, long ideLeaseRound) {
    if (!identity.equals(transport.connectionIdentity(environmentId).orElse(null))) {
      return;
    }
    EnvironmentIdeViewsDTO snapshot;
    try {
      snapshot = localEnvironmentIdeService.listViews(environmentId);
    } catch (RuntimeException error) {
      // LIST_VIEWS 失败时这一轮不写任何证明；等下一次正常心跳，绝不重放旧轮次。
      return;
    }
    List<EnvironmentIdeViewDTO> views = snapshot.getViews();
    if (views == null) {
      return;
    }
    Map<Group, List<EnvironmentIdeViewDTO>> groups = new LinkedHashMap<>();
    for (EnvironmentIdeViewDTO view : views) {
      try {
        Group group =
            new Group(
                parseUuid(view.getEventNodeId(), "eventNodeId"),
                parseUuid(view.getConnectionId(), "connectionId"),
                parseUuid(view.getDocumentId(), "documentId"));
        groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(view);
      } catch (IllegalArgumentException ignored) {
        // UI 服务返回的 DTO 已在写入时校验；这里仍然 fail-closed 地跳过坏条目。
      }
    }

    List<DaemonIdeLease.Binding> bindings = new ArrayList<>();
    for (Map.Entry<Group, List<EnvironmentIdeViewDTO>> entry : groups.entrySet()) {
      Group group = entry.getKey();
      Optional<IdeBrowserUseGateway.Proofs> proofs;
      try {
        proofs =
            browserUse.activeBoundProofs(group.eventNodeId, group.connectionId, group.documentId);
      } catch (RuntimeException error) {
        // 事件节点不可达/查询失败：该组不续租，不把失败当成不活跃以外的任何事实。
        continue;
      }
      if (proofs.isEmpty()) {
        continue;
      }
      for (EnvironmentIdeViewDTO view : entry.getValue()) {
        try {
          UUID viewId = parseUuid(view.getViewId(), "viewId");
          UUID documentId = parseUuid(view.getDocumentId(), "documentId");
          long epoch = IdeCommand.parseBindingEpoch(view.getBindingEpoch());
          if (hasExactProof(proofs.get(), environmentId, viewId, documentId, epoch)) {
            bindings.add(new DaemonIdeLease.Binding(viewId, documentId, epoch));
          }
        } catch (IllegalArgumentException ignored) {
          // 无有效 epoch 的意图不构成使用证明。
        }
      }
    }

    if (!identity.equals(transport.connectionIdentity(environmentId).orElse(null))) {
      return;
    }
    try {
      transport.sendIdeLease(environmentId, new DaemonIdeLease(ideLeaseRound, bindings));
    } catch (RuntimeException error) {
      // 发送失败只影响本轮；下一次正常心跳重新核对，不自动重试旧轮次。
    }
  }

  private static boolean hasExactProof(
      IdeBrowserUseGateway.Proofs proofs,
      EnvironmentId environmentId,
      UUID viewId,
      UUID documentId,
      long bindingEpoch) {
    for (IdeBrowserUseGateway.Proof proof : proofs.views()) {
      if (proof.environmentId().equals(environmentId)
          && proof.viewId().equals(viewId)
          && proof.documentId().equals(documentId)
          && proof.bindingEpoch() == bindingEpoch) {
        return true;
      }
    }
    return false;
  }

  private static UUID parseUuid(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is missing");
    }
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " is not canonical");
    }
    return parsed;
  }

  private record Group(UUID eventNodeId, UUID connectionId, UUID documentId) {}
}
