package fun.fengwk.kkstudio.ide.browser;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;
import java.util.UUID;

/**
 * 精确查询“连接/document 是否仍存活且登记了完全相同的打开/恢复意图”的内部请求体。
 *
 * <p>wire 上全部使用 canonical 字符串，避免 UUID 大小写与数值 revision 精度差异。
 */
public record IdeBrowserUseIntentQuery(
    String eventNodeId,
    String connectionId,
    String documentId,
    String environmentId,
    String viewId,
    String claimId) {

  public ConnectionIdentity identity() {
    return ConnectionIdentity.parse(eventNodeId, connectionId);
  }

  public UUID document() {
    return ConnectionIdentity.parseCanonicalUuid(documentId, "documentId");
  }

  public EnvironmentId environment() {
    return EnvironmentId.parse(environmentId);
  }

  public UUID view() {
    return ConnectionIdentity.parseCanonicalUuid(viewId, "viewId");
  }

  public UUID claim() {
    return ConnectionIdentity.parseCanonicalUuid(claimId, "claimId");
  }

  public static IdeBrowserUseIntentQuery of(
      ConnectionIdentity identity,
      UUID documentId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    return new IdeBrowserUseIntentQuery(
        identity.eventNodeId().toString(),
        identity.connectionId().toString(),
        documentId.toString(),
        environmentId.toString(),
        viewId.toString(),
        claimId.toString());
  }
}
