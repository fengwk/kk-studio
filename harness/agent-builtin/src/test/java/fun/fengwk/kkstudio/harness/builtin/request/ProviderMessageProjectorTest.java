package fun.fengwk.kkstudio.harness.builtin.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;

import java.util.List;

class ProviderMessageProjectorTest {

  @Test
  void preservesHistoricalCallsAndResultsWhenToolIsUnavailableAndEnvironmentChanged() {
    ToolCallMessageContent call =
        new ToolCallMessageContent("call-1", "removed_tool", "removed", "{}");
    ToolResultMessageContent toolResult =
        new ToolResultMessageContent(
            "call-1",
            "removed_tool",
            "removed",
            List.of(new TextMessageContent("historical result")),
            true,
            "{\"error\":true}");
    List<ProviderMessage> projected =
        new ProviderMessageProjector()
            .project(
                List.of(
                    new AgentMessage(AgentMessageRole.ASSISTANT, List.of(call)),
                    new AgentMessage(AgentMessageRole.TOOL, List.of(toolResult))));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL), roles(projected));
    assertEquals(
        List.of(new ProviderToolCallBlock(new ProviderToolCall("call-1", "removed_tool", "{}"))),
        projected.get(0).contents());
    assertEquals(
        List.of(
            new ProviderToolResultBlock(
                "call-1",
                "removed_tool",
                List.of(new ProviderTextBlock("historical result")),
                true,
                "{\"error\":true}")),
        projected.get(1).contents());
  }

  @Test
  void preservesMultipleHistoricalCallAndResultOrder() {
    List<ProviderMessage> projected =
        new ProviderMessageProjector()
            .project(
                List.of(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(
                            new ToolCallMessageContent("call-1", "old_a", "tool", "{}"),
                            new ToolCallMessageContent("call-2", "old_b", "tool", "{}"))),
                    toolResult("call-2", "old_b", "second"),
                    toolResult("call-1", "old_a", "first")));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.TOOL),
        roles(projected));
    assertEquals(
        List.of(
            new ProviderToolCallBlock(new ProviderToolCall("call-1", "old_a", "{}")),
            new ProviderToolCallBlock(new ProviderToolCall("call-2", "old_b", "{}"))),
        projected.get(0).contents());
    assertEquals(
        "call-2", ((ProviderToolResultBlock) projected.get(1).contents().getFirst()).toolCallId());
    assertEquals(
        "call-1", ((ProviderToolResultBlock) projected.get(2).contents().getFirst()).toolCallId());
  }

  @Test
  void closesHistoricalCallWithoutStoredResultUsingSyntheticErrorResult() {
    List<ProviderMessage> projected =
        new ProviderMessageProjector()
            .project(
                List.of(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(
                            new ToolCallMessageContent("call-1", "removed_tool", "tool", "{}"))),
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("next")))));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.USER),
        roles(projected));
    assertEquals(
        List.of(
            new ProviderToolResultBlock(
                "call-1",
                "removed_tool",
                List.of(new ProviderTextBlock("No result provided")),
                true,
                null)),
        projected.get(1).contents());
  }

  private static AgentMessage toolResult(String callId, String toolName, String text) {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                callId, toolName, "tool", List.of(new TextMessageContent(text)), false, "{}")));
  }

  private static List<ProviderMessageRole> roles(List<ProviderMessage> messages) {
    return messages.stream().map(ProviderMessage::role).toList();
  }
}
