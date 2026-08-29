package fun.fengwk.kkstudio.harness.daemon.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;

import java.util.Objects;
import java.util.Set;

/** 可在重复 INVOKE 或重连后重发的终态 wire 消息。 */
public record DaemonTerminalMessage(DaemonMessageType messageType, String payloadJson) {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<DaemonMessageType> TERMINAL_TYPES =
      Set.of(DaemonMessageType.COMPLETED, DaemonMessageType.FAILED, DaemonMessageType.CANCELLED);

  public DaemonTerminalMessage {
    messageType = Objects.requireNonNull(messageType, "messageType");
    if (!TERMINAL_TYPES.contains(messageType)) {
      throw new IllegalArgumentException("messageType must be terminal");
    }
    payloadJson = requireJsonObject(payloadJson);
  }

  private static String requireJsonObject(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("payloadJson must not be blank");
    }
    try {
      JsonNode node = MAPPER.readTree(json);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("payloadJson must be a JSON object");
      }
      return json;
    } catch (Exception error) {
      throw new IllegalArgumentException(
          "payloadJson must be valid JSON: " + error.getMessage(), error);
    }
  }
}
