package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.util.List;

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
                        new ImageMessageContent("image/png", "image-data"),
                        new AudioMessageContent("audio/mpeg", "audio-data"),
                        new ThinkingMessageContent("reasoning"),
                        new JsonMessageContent("{\"answer\":true}"),
                        new ResourceMessageContent(
                            new ResourceRef(
                                "https://example.test/report.txt",
                                "text/plain",
                                "report",
                                3L,
                                null),
                            "resource preview"),
                        new ToolCallMessageContent("call-1", "lookup", "{\"key\":\"value\"}"))),
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
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
            new ProviderImageBlock("image/png", "image-data"),
            new ProviderAudioBlock("audio/mpeg", "audio-data"),
            new ProviderThinkingBlock("reasoning"),
            new ProviderJsonBlock("{\"answer\":true}"),
            new ProviderTextBlock("[Resource report]\nresource preview"),
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

  /** Resource 投影为有界小文本标记：display name 优先；data URI 用 inline mediaType；长 URI ASCII 截断到 512 字符。 */
  @Test
  void projectsBoundedResourceMarkersWithoutInjectingLargeUris() {
    ProviderMessageProjector projector = new ProviderMessageProjector();
    String longUri = "https://example.test/" + "segment/".repeat(100) + "tail.txt";
    assertTrue(longUri.length() > ProviderMessageProjector.MAX_URI_LABEL_CHARS);

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ResourceMessageContent(
                            new ResourceRef(
                                "https://example.test/a.txt", "text/plain", "a.txt", null, null),
                            null),
                        new ResourceMessageContent(
                            new ResourceRef(
                                "https://example.test/b.txt", "text/plain", null, null, null),
                            "preview"),
                        new ResourceMessageContent(
                            new ResourceRef(
                                "data:text/plain,hello",
                                "text/plain",
                                null,
                                5L,
                                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                            "data preview"),
                        new ResourceMessageContent(
                            new ResourceRef(longUri, "text/plain", null, null, null), null)))));

    String truncated =
        longUri.substring(0, ProviderMessageProjector.MAX_URI_LABEL_CHARS - 3) + "...";
    assertEquals(
        List.of(
            new ProviderTextBlock("[Resource a.txt]\n"),
            new ProviderTextBlock("[Resource https://example.test/b.txt]\npreview"),
            new ProviderTextBlock("[Resource inline text/plain]\ndata preview"),
            new ProviderTextBlock("[Resource " + truncated + "]\n")),
        projected.get(0).contents());
  }

  /** display name 优先于 data URI：data 资源带 name 时绝不投影 inline/inline 载荷。 */
  @Test
  void resourceNameWinsOverDataUri() {
    ProviderMessageProjector projector = new ProviderMessageProjector();

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ResourceMessageContent(
                            new ResourceRef(
                                "data:text/plain,hello",
                                "text/plain",
                                "hello.txt",
                                5L,
                                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                            null)))));

    assertEquals(
        List.of(new ProviderTextBlock("[Resource hello.txt]\n")), projected.get(0).contents());
  }

  private static AgentMessage assistantToolCall(String toolCallId) {
    return new AgentMessage(
        AgentMessageRole.ASSISTANT,
        List.of(new ToolCallMessageContent(toolCallId, "lookup", "{}")));
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
