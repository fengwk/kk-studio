package fun.fengwk.kkstudio.harness.tool.daemon;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

import java.util.Objects;
import java.util.Set;

/** Platform 与 Daemon 间每个 JSON 消息的版本化 envelope。 */
public record DaemonEnvelope(
    int protocolVersion,
    DaemonMessageType messageType,
    EnvironmentId environmentId,
    String environmentName,
    String invocationId,
    long sequence,
    String payloadJson) {

  /** environmentName 仅用于展示，但必须可安全上 wire：UTF-8 编码不超过 256 字节。 */
  public static final int MAX_ENVIRONMENT_NAME_UTF8_BYTES = 256;

  private static final Set<DaemonMessageType> INVOCATION_MESSAGES =
      Set.of(
          DaemonMessageType.INVOKE,
          DaemonMessageType.CANCEL,
          DaemonMessageType.STARTED,
          DaemonMessageType.PARTIAL,
          DaemonMessageType.COMPLETED,
          DaemonMessageType.FAILED,
          DaemonMessageType.CANCELLED,
          DaemonMessageType.LOAD_SKILL,
          DaemonMessageType.SKILL_LOADED,
          DaemonMessageType.SKILL_LOAD_FAILED);

  public DaemonEnvelope {
    if (protocolVersion <= 0) {
      throw new IllegalArgumentException("protocolVersion must be positive");
    }
    messageType = Objects.requireNonNull(messageType, "messageType");
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    environmentName = requireCanonicalEnvironmentName(environmentName);
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

  /** environmentName 是 display-only 字段，但必须无周边空白、无 ISO control/未配对代理项，且 UTF-8 不超限。 */
  private static String requireCanonicalEnvironmentName(String environmentName) {
    if (environmentName == null
        || environmentName.isBlank()
        || !environmentName.strip().equals(environmentName)) {
      throw new IllegalArgumentException(
          "environmentName must be non-blank without surrounding whitespace");
    }
    if (environmentName.codePoints().anyMatch(DaemonEnvelope::isWireUnsafeCodePoint)) {
      throw new IllegalArgumentException(
          "environmentName must not contain ISO control characters or unpaired surrogates");
    }
    if (ResourceRef.utf8Length(environmentName, "environmentName")
        > MAX_ENVIRONMENT_NAME_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "environmentName must not exceed " + MAX_ENVIRONMENT_NAME_UTF8_BYTES + " UTF-8 bytes");
    }
    return environmentName;
  }

  private static boolean isWireUnsafeCodePoint(int codePoint) {
    return codePoint < 0x20
        || (codePoint >= 0x7F && codePoint <= 0x9F)
        || (codePoint >= 0xD800 && codePoint <= 0xDFFF);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
