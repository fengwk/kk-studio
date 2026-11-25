package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 浏览器事件节点使用登记的窄查询端口。
 *
 * <p>生产实现委托 {@code IdeBrowserUseClient}：本节点是事件节点时读本地登记，否则按 {@code eventNodeId} 单跳认证查询。查询失败必须抛出不可用，
 * 不能用「没有证据」伪造成「没有使用」；claim 前只接受完全相同的 environment/view/browser session/claim 意图。
 */
public class IdeBrowserUseGateway {

  private final IdeBrowserUseClient client;

  public IdeBrowserUseGateway(IdeBrowserUseClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /** 无用户身份时不能验证意图，固定失败关闭；调用方必须使用带 userId 的重载。 */
  public boolean findExactIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID browserSessionId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    return false;
  }

  /** Exact intent requires the user authenticated on the event WebSocket. */
  public boolean findExactIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID browserSessionId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId,
      UUID userId) {
    Objects.requireNonNull(eventNodeId, "eventNodeId");
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(browserSessionId, "browserSessionId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    try {
      return client.findExactIntent(
          new ConnectionIdentity(eventNodeId, connectionId),
          browserSessionId,
          environmentId,
          viewId,
          claimId,
          userId);
    } catch (DomainException error) {
      if (error.code() == DomainErrorCode.UNAVAILABLE && "IDE_UNAVAILABLE".equals(error.reason())) {
        throw error;
      }
      throw unavailable("browser IDE usage intent is unavailable", error);
    } catch (RuntimeException error) {
      throw unavailable("browser IDE usage intent is unavailable", error);
    }
  }

  /** 无用户身份时不能验证续租证明，固定失败关闭；调用方必须使用带 userId 的重载。 */
  public Optional<Proofs> activeBoundProofs(
      UUID eventNodeId, UUID connectionId, UUID browserSessionId) {
    return Optional.empty();
  }

  /** Returns active proofs only for the user authenticated by the event WebSocket. */
  public Optional<Proofs> activeBoundProofs(
      UUID eventNodeId, UUID connectionId, UUID browserSessionId, UUID userId) {
    Objects.requireNonNull(eventNodeId, "eventNodeId");
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(browserSessionId, "browserSessionId");
    try {
      Optional<IdeBrowserUse> usage =
          client.activeBoundProofs(
              new ConnectionIdentity(eventNodeId, connectionId), browserSessionId, userId);
      return usage.map(value -> toProofs(browserSessionId, userId, value));
    } catch (DomainException error) {
      if (error.code() == DomainErrorCode.UNAVAILABLE && "IDE_UNAVAILABLE".equals(error.reason())) {
        throw error;
      }
      throw unavailable("browser IDE usage proofs are unavailable", error);
    } catch (RuntimeException error) {
      throw unavailable("browser IDE usage proofs are unavailable", error);
    }
  }

  private static Proofs toProofs(
      UUID expectedBrowserSessionId, UUID userId, IdeBrowserUse usage) {
    if (!usage.browserSessionId().equals(expectedBrowserSessionId)) {
      throw unavailable("browser event node returned a mismatched browser session usage", null);
    }
    List<Proof> proofs = new ArrayList<>(usage.views().size());
    for (IdeBrowserUseView view : usage.views()) {
      Long bindingEpoch = view.bindingEpoch();
      if (bindingEpoch == null) {
        continue;
      }
      proofs.add(
          new Proof(
              view.environmentId(),
              view.viewId(),
              usage.browserSessionId(),
              userId,
              bindingEpoch));
    }
    return new Proofs(usage.browserSessionId(), userId, usage.revision(), proofs);
  }

  private static DomainException unavailable(String message, Throwable cause) {
    return cause == null
        ? DomainException.unavailable("IDE_UNAVAILABLE", message)
        : DomainException.unavailable("IDE_UNAVAILABLE", message, cause);
  }

  /** 一个 browserSession 当前带 epoch 的绑定列表（可能包含其它 Environment；调用方按 environment 过滤）。 */
  public record Proofs(UUID browserSessionId, UUID userId, long revision, List<Proof> views) {

    public Proofs {
      if (revision <= 0) {
        throw new IllegalArgumentException("revision must be positive");
      }
      views = List.copyOf(views);
    }
  }

  /** 单条已绑定使用证明。 */
  public record Proof(
      EnvironmentId environmentId,
      UUID viewId,
      UUID browserSessionId,
      UUID userId,
      long bindingEpoch) {

    public Proof {
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
    }
  }
}
