package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Objects;
import java.util.UUID;

/**
 * 仅由服务端使用的真实浏览器连接来源/收件人元数据。
 *
 * <p>{@code appNodeId} 标识 App 节点，{@code connectionId} 标识该节点上的一次 WS 连接；它由服务端从可信连接上下文填充，浏览器不能自行声明。
 * {@code viewerId} 只出现在内层 command/event 中。连接 id 非空、不超过 {@value #MAX_CONNECTION_ID_LENGTH}
 * 个字符且不含控制字符。
 *
 * @param appNodeId App 节点 id
 * @param connectionId 服务端观察到的连接标识
 */
public record TerminalRoute(UUID appNodeId, String connectionId) {

  /** connectionId 的最大字符数。 */
  public static final int MAX_CONNECTION_ID_LENGTH = 128;

  public TerminalRoute {
    Objects.requireNonNull(appNodeId, "appNodeId");
    if (connectionId == null || connectionId.isEmpty()) {
      throw new IllegalArgumentException("connectionId must not be empty");
    }
    if (connectionId.length() > MAX_CONNECTION_ID_LENGTH) {
      throw new IllegalArgumentException(
          "connectionId must not exceed " + MAX_CONNECTION_ID_LENGTH + " characters");
    }
    if (connectionId.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("connectionId must not contain control characters");
    }
  }
}
