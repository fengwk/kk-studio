package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.List;

/**
 * Provider projection must repair every orphan occurrence without retaining ToolCall IDs globally.
 */
class ProviderMessageProjectorTest {

  /**
   * A resolved chain preserves every supported content type and needs no synthetic TOOL message.
   */
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
                        new ArtifactMessageContent("artifact-empty", "text/plain", null),
                        new ArtifactMessageContent("artifact-preview", "text/plain", "preview"),
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
            new ProviderTextBlock("[Artifact artifact-empty (text/plain)]\n"),
            new ProviderTextBlock("[Artifact artifact-preview (text/plain)]\npreview"),
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

  /**
   * Role boundaries and request end both repair only the currently open occurrence of a reused ID.
   */
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
