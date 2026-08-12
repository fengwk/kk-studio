package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.List;
import java.util.UUID;

/** Provider 投影必须修复每一个 orphan 出现位置，但全局不持有 ToolCall ID。 */
class ProviderMessageProjectorTest {

  /** 已解析的 tool chain 保留所有受支持的 content 类型，无需合成 TOOL message。 */
  @Test
  void projectsResolvedToolChainAndAllSupportedContents() {
    ProviderMessageProjector projector = new ProviderMessageProjector();

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                AgentMessage.system("system"),
                new AgentMessage(
                    AgentMessageRole.USER, List.of(new TextMessageContent("question"))),
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new TextMessageContent("answer"),
                        new ThinkingMessageContent("reasoning"),
                        new JsonMessageContent("{\"answer\":true}"),
                        new ResourceMessageContent(new UUID(0L, 1L), "report", "resource preview"),
                        new TextMessageContent("second text"),
                        new ToolCallMessageContent(
                            "call-1", "lookup", "lookup", "{\"key\":\"value\"}"))),
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
                            "lookup",
                            "lookup",
                            List.of(
                                new TextMessageContent("tool output"),
                                new JsonMessageContent("{\"found\":true}")),
                            true,
                            "{\"code\":\"NOT_FOUND\"}")))));

    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.USER,
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals(List.of(new ProviderTextBlock("system")), projected.get(0).contents());
    assertEquals(List.of(new ProviderTextBlock("question")), projected.get(1).contents());
    assertEquals(
        List.of(
            new ProviderTextBlock("answer"),
            new ProviderThinkingBlock("reasoning"),
            new ProviderJsonBlock("{\"answer\":true}"),
            new ProviderResourceBlock(new UUID(0L, 1L), "report", "resource preview"),
            new ProviderTextBlock("second text"),
            new ProviderToolCallBlock(
                new ProviderToolCall("call-1", "lookup", "{\"key\":\"value\"}"))),
        projected.get(2).contents());
    assertEquals(
        List.of(
            new ProviderToolResultBlock(
                "call-1",
                "lookup",
                List.of(
                    new ProviderTextBlock("tool output"),
                    new ProviderJsonBlock("{\"found\":true}")),
                true,
                "{\"code\":\"NOT_FOUND\"}")),
        projected.get(3).contents());
  }

  /** Role 边界与 request 末尾都只修复当前打开的、复用 ID 的那次出现位置。 */
  @Test
  void synthesizesErrorForEachSubsequentOrphanWithTheSameToolCallId() {
    ProviderMessageProjector projector = new ProviderMessageProjector();

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                assistantToolCall("call-1"),
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("steer"))),
                assistantToolCall("call-1")));

    assertEquals(
        List.of(
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL,
            ProviderMessageRole.USER,
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertSyntheticOrphanResult(projected.get(1));
    assertSyntheticOrphanResult(projected.get(4));
  }

  /**
   * Resource 投影为 durable-safe 的 ProviderResourceBlock（blobId/name/preview），绝不注入 URI / mediaType /
   * size。
   */
  @Test
  void projectsDurableResourceBlocksPreservingBlobFacts() {
    ProviderMessageProjector projector = new ProviderMessageProjector();

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ResourceMessageContent(new UUID(0L, 1L), "a.txt", null),
                        new ResourceMessageContent(new UUID(0L, 2L), "b.txt", "preview")))));

    assertEquals(
        List.of(
            new ProviderResourceBlock(new UUID(0L, 1L), "a.txt", ""),
            new ProviderResourceBlock(new UUID(0L, 2L), "b.txt", "preview")),
        projected.get(0).contents());
  }

  private static AgentMessage assistantToolCall(String toolCallId) {
    return new AgentMessage(
        AgentMessageRole.ASSISTANT,
        List.of(new ToolCallMessageContent(toolCallId, "lookup", "lookup", "{}")));
  }

  private static void assertSyntheticOrphanResult(ProviderMessage message) {
    assertEquals(1, message.contents().size());
    assertTrue(message.contents().get(0) instanceof ProviderToolResultBlock);
    ProviderToolResultBlock result = (ProviderToolResultBlock) message.contents().get(0);
    assertEquals("call-1", result.toolCallId());
    assertEquals("lookup", result.toolName());
    assertTrue(result.error());
    assertEquals("{}", result.detailsJson());
    assertEquals("No result provided", ((ProviderTextBlock) result.contents().get(0)).text());
  }
}
