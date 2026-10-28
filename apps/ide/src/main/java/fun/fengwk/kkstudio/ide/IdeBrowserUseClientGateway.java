package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseView;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 生产 gateway：把事件节点 typed usage 查询映射为控制服务只需要的窄意图/证明。
 *
 * <p>连接身份由 {@code eventNodeId,connectionId} 组成；查询失败保持不可用语义，绝不把“没有结果”伪造成“没有使用”。证明只保留带正 epoch
 * 的持有项，并按 client 返回的 revision 原样交给租约协调器。
 */
public final class IdeBrowserUseClientGateway implements IdeBrowserUseGateway {

  private final IdeBrowserUseClient client;

  public IdeBrowserUseClientGateway(IdeBrowserUseClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public boolean findExactIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID documentId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    Objects.requireNonNull(eventNodeId, "eventNodeId");
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    try {
      return client.findExactIntent(
          new ConnectionIdentity(eventNodeId, connectionId),
          documentId,
          environmentId,
          viewId,
          claimId);
    } catch (AiEnvironmentIdeUnavailableException error) {
      throw error;
    } catch (RuntimeException error) {
      throw unavailable("browser IDE usage intent is unavailable", error);
    }
  }

  @Override
  public Optional<Proofs> activeBoundProofs(
      UUID eventNodeId, UUID connectionId, UUID documentId) {
    Objects.requireNonNull(eventNodeId, "eventNodeId");
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(documentId, "documentId");
    try {
      Optional<IdeBrowserUse> usage =
          client.activeBoundProofs(
              new ConnectionIdentity(eventNodeId, connectionId), documentId);
      return usage.map(value -> toProofs(documentId, value));
    } catch (AiEnvironmentIdeUnavailableException error) {
      throw error;
    } catch (RuntimeException error) {
      throw unavailable("browser IDE usage proofs are unavailable", error);
    }
  }

  private static Proofs toProofs(UUID expectedDocumentId, IdeBrowserUse usage) {
    if (!usage.documentId().equals(expectedDocumentId)) {
      throw unavailable("browser event node returned a mismatched document usage", null);
    }
    List<Proof> proofs = new ArrayList<>(usage.views().size());
    for (IdeBrowserUseView view : usage.views()) {
      Long bindingEpoch = view.bindingEpoch();
      if (bindingEpoch == null) {
        // activeBoundProofs 只应返回已绑定项；未绑定意图不能成为续租证明。
        continue;
      }
      proofs.add(
          new Proof(view.environmentId(), view.viewId(), usage.documentId(), bindingEpoch));
    }
    if (proofs.isEmpty()) {
      throw unavailable("browser event node returned no active binding proof", null);
    }
    return new Proofs(usage.documentId(), usage.revision(), proofs);
  }

  private static AiEnvironmentIdeUnavailableException unavailable(String message, Throwable cause) {
    return cause == null
        ? new AiEnvironmentIdeUnavailableException(message)
        : new AiEnvironmentIdeUnavailableException(message, cause);
  }
}
