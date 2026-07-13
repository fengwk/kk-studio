package fun.fengwk.kkstudio.harness.daemon.journal;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;
import java.util.Objects;
import java.util.Set;

/** 可在重复 INVOKE 或重连后重发的终态 wire 消息。 */
public record DaemonTerminalMessage(DaemonMessageType messageType, String payloadJson) {

  private static final Set<DaemonMessageType> TERMINAL_TYPES =
      Set.of(
          DaemonMessageType.COMPLETED, DaemonMessageType.FAILED, DaemonMessageType.CANCELLED);

  public DaemonTerminalMessage {
    messageType = Objects.requireNonNull(messageType, "messageType");
    if (!TERMINAL_TYPES.contains(messageType)) {
      throw new IllegalArgumentException("messageType must be terminal");
    }
    payloadJson = ToolArgumentsValidator.requireJsonObject(payloadJson);
  }
}
