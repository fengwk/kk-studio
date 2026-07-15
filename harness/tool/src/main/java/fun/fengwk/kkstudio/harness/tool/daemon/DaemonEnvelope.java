package fun.fengwk.kkstudio.harness.tool.daemon;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;
import java.util.Objects;
import java.util.Set;

/** Cloud 与 Daemon 间每个 JSON 消息的版本化 envelope。 */
public record DaemonEnvelope(
    int protocolVersion,
    DaemonMessageType messageType,
    String environmentId,
    String invocationId,
    long sequence,
    String payloadJson) {

  private static final Set<DaemonMessageType> INVOCATION_MESSAGES =
      Set.of(
          DaemonMessageType.INVOKE,
          DaemonMessageType.CANCEL,
          DaemonMessageType.STARTED,
          DaemonMessageType.PARTIAL,
          DaemonMessageType.COMPLETED,
          DaemonMessageType.FAILED,
          DaemonMessageType.CANCELLED);

  public DaemonEnvelope {
    if (protocolVersion <= 0) {
      throw new IllegalArgumentException("protocolVersion must be positive");
    }
    messageType = Objects.requireNonNull(messageType, "messageType");
    environmentId = requireNonBlank(environmentId, "environmentId");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    if (INVOCATION_MESSAGES.contains(messageType)) {
      invocationId = requireNonBlank(invocationId, "invocationId");
    } else if (invocationId != null && invocationId.isBlank()) {
      throw new IllegalArgumentException("invocationId must not be blank when present");
    }
    payloadJson = ToolArgumentsValidator.requireJsonObject(payloadJson);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
