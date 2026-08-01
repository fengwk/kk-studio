package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.math.BigDecimal;
import java.util.List;

/** Message/custom payload role invariants and request-reference ownership. */
class RuntimeEntryPayloadTest {

  private static final TurnSettings SETTINGS = new TurnSettings("agent", null, false);

  @Test
  void userAndCustomMessagesCarryTurnSettings() {
    MessageEntryPayload user = new MessageEntryPayload(userMessage("hello"), SETTINGS, null);
    CustomMessageEntryPayload custom =
        new CustomMessageEntryPayload(systemMessage("instruction"), SETTINGS);

    assertEquals(SETTINGS, user.turnSettings());
    assertEquals(SETTINGS, custom.turnSettings());
  }

  @Test
  void assistantAndToolMessagesDoNotCarryTurnSettings() {
    MessageEntryPayload assistant =
        new MessageEntryPayload(
            assistantMessage("answer"), null, completedMetadata(ProviderStopReason.COMPLETED));
    MessageEntryPayload tool = new MessageEntryPayload(toolMessage(), null, null);

    assertEquals(null, assistant.turnSettings());
    assertEquals(null, tool.turnSettings());
  }

  @Test
  void rejectsWrongReferenceAndRoleCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(userMessage("hello"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessageEntryPayload(
                assistantMessage("answer"),
                SETTINGS,
                completedMetadata(ProviderStopReason.COMPLETED)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(systemMessage("system"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomMessageEntryPayload(assistantMessage("assistant"), SETTINGS));
    assertThrows(
        NullPointerException.class,
        () -> new CustomMessageEntryPayload(systemMessage("system"), null));
  }

  @Test
  void assistantToolCallsRequireToolCallsStopReason() {
    AgentMessage withToolCall =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "tool", "{}")));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessageEntryPayload(
                withToolCall, null, completedMetadata(ProviderStopReason.COMPLETED)));
    assertEquals(
        AgentMessageRole.ASSISTANT,
        new MessageEntryPayload(
                withToolCall, null, completedMetadata(ProviderStopReason.TOOL_CALLS))
            .message()
            .role());
  }

  private static AgentMessage userMessage(String text) {
    return message(AgentMessageRole.USER, text);
  }

  private static AgentMessage systemMessage(String text) {
    return message(AgentMessageRole.SYSTEM, text);
  }

  private static AgentMessage assistantMessage(String text) {
    return message(AgentMessageRole.ASSISTANT, text);
  }

  private static AgentMessage toolMessage() {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                "call-1", "tool", List.of(new TextMessageContent("ok")), false, "{}")));
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata completedMetadata(ProviderStopReason stopReason) {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(2));
    return new AssistantMessageMetadata(stopReason, usage, cost);
  }
}
