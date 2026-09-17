package fun.fengwk.kkstudio.harness.environment.daemon;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;
import java.util.Set;

/**
 * Platform 与 Daemon 间每个 JSON 消息的 envelope：只描述关联标识，不承载传输顺序。
 *
 * <p>{@code environmentId} 是 nullable scope：HELLO 在认证前不知道目标 Environment（必须为 null）；WELCOME/READY/
 * HEARTBEAT 与所有调用消息都由已绑定连接发出（必须非空）；ERROR 在握手失败时可能没有绑定 scope（可空）。
 *
 * <p>资源上传控制消息（{@code RESOURCE_UPLOAD_REQUEST} / {@code RESOURCE_UPLOAD_TICKET} / {@code
 * RESOURCE_UPLOAD_COMMIT}）以 {@code invocationId} 关联到具体调用，但 {@code transferId} 由 payload
 * 承载：同一调用可以有多个 并发/串行传输，因此它们不是「调用编号别名」。
 *
 * <p>本协议没有全局序号或 ACK：消息可靠性来自 invocationId 与 Daemon journal，而不是传输层确认。
 */
public record DaemonEnvelope(
    int protocolVersion,
    DaemonMessageType messageType,
    EnvironmentId environmentId,
    String invocationId,
    String payloadJson) {

  private static final Set<DaemonMessageType> BOUND_MESSAGES =
      Set.of(
          DaemonMessageType.WELCOME,
          DaemonMessageType.READY,
          DaemonMessageType.HEARTBEAT,
          DaemonMessageType.INVOKE,
          DaemonMessageType.CANCEL,
          DaemonMessageType.STARTED,
          DaemonMessageType.PROGRESS,
          DaemonMessageType.COMPLETED,
          DaemonMessageType.FAILED,
          DaemonMessageType.CANCELLED,
          DaemonMessageType.RESOURCE_UPLOAD_REQUEST,
          DaemonMessageType.RESOURCE_UPLOAD_TICKET,
          DaemonMessageType.RESOURCE_UPLOAD_COMMIT);

  private static final Set<DaemonMessageType> INVOCATION_MESSAGES =
      Set.of(
          DaemonMessageType.INVOKE,
          DaemonMessageType.CANCEL,
          DaemonMessageType.STARTED,
          DaemonMessageType.PROGRESS,
          DaemonMessageType.COMPLETED,
          DaemonMessageType.FAILED,
          DaemonMessageType.CANCELLED,
          DaemonMessageType.RESOURCE_UPLOAD_REQUEST,
          DaemonMessageType.RESOURCE_UPLOAD_TICKET,
          DaemonMessageType.RESOURCE_UPLOAD_COMMIT);

  public DaemonEnvelope {
    if (protocolVersion != DaemonProtocol.VERSION) {
      throw new IllegalArgumentException("unsupported protocolVersion: " + protocolVersion);
    }
    messageType = Objects.requireNonNull(messageType, "messageType");
    if (messageType == DaemonMessageType.HELLO) {
      if (environmentId != null) {
        throw new IllegalArgumentException("HELLO envelope must not declare environmentId");
      }
    } else if (BOUND_MESSAGES.contains(messageType)) {
      environmentId = Objects.requireNonNull(environmentId, "environmentId");
    }
    if (INVOCATION_MESSAGES.contains(messageType)) {
      invocationId = requireNonBlank(invocationId, "invocationId");
    } else if (invocationId != null && invocationId.isBlank()) {
      throw new IllegalArgumentException("invocationId must not be blank when present");
    }
    payloadJson = JsonValues.requireJsonObject(payloadJson, "payloadJson");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
