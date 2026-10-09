package fun.fengwk.kkstudio.harness.daemon.terminal;

import java.util.Objects;
import java.util.UUID;

/**
 * writer 控制权的可信上游身份：同一 App 节点上的一个观察连接与一个浏览器页面。
 *
 * <p>{@code appNodeId} 与 {@code viewerId} 标识节点与页面，{@code connectionId} 标识本次 WS 连接且非空、最长 {@value
 * #MAX_CONNECTION_ID_LENGTH} 字符。三个字段共同参与授权判断：仅凭 {@code viewerId} 不能索取他人的 writer token，同一页面的新连接必须凭旧
 * secret 走跨连接恢复。
 */
public record WriterOwner(UUID appNodeId, String connectionId, UUID viewerId) {

  /** connectionId 的最大字符数。 */
  public static final int MAX_CONNECTION_ID_LENGTH = 128;

  public WriterOwner {
    Objects.requireNonNull(appNodeId, "appNodeId");
    Objects.requireNonNull(viewerId, "viewerId");
    if (connectionId == null || connectionId.isEmpty()) {
      throw new IllegalArgumentException("connectionId must not be empty");
    }
    if (connectionId.length() > MAX_CONNECTION_ID_LENGTH) {
      throw new IllegalArgumentException(
          "connectionId must not exceed " + MAX_CONNECTION_ID_LENGTH + " characters");
    }
    if (connectionId.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("connectionId must not contain NUL");
    }
  }
}
