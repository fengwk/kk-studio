package fun.fengwk.kkstudio.ide.browser;

import java.util.Objects;
import java.util.UUID;

/**
 * 浏览器应用事件连接的物理身份：建立连接的事件节点与本次连接的随机 UUID。
 *
 * <p>两者都只是路由/关联键，不是认证凭据。每次 WebSocket 重建都会得到新的 {@code connectionId}，因此旧连接关闭只能撤销自己的状态。
 */
public record ConnectionIdentity(UUID eventNodeId, UUID connectionId) {

  public ConnectionIdentity {
    eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
    connectionId = Objects.requireNonNull(connectionId, "connectionId");
  }

  /** 解析两个 canonical UUID 文本；大小写变体或非 canonical 形状一律拒绝。 */
  public static ConnectionIdentity parse(String eventNodeId, String connectionId) {
    return new ConnectionIdentity(
        parseCanonicalUuid(eventNodeId, "eventNodeId"),
        parseCanonicalUuid(connectionId, "connectionId"));
  }

  public static UUID parseCanonicalUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    return parsed;
  }
}
