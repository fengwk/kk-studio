package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplateLoader;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CompactionPrompts 契约：全部 prompt 文本与 classpath 资源逐字一致（Pi 逐字复制），严格渲染 conversation / previousSummary
 * / summary 变量，Pi 风格对话序列化（类型化 section、媒体占位符、Tool result 2_000 字符截断）。
 */
class CompactionPromptsTest {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/runtime/compaction/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();

  @Test
  void systemPromptMatchesResourceVerbatim() {
    assertEquals(
        resource("summarization-system.md"), CompactionPrompts.summarizationSystemPrompt());
  }

  @Test
  void summaryUserPromptFramesInitialAndUpdateVariants() {
    List<AgentMessage> messages =
        List.of(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))),
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hello"))));
    String conversation = CompactionPrompts.serializeConversation(messages);

    String initial = CompactionPrompts.summaryUserPrompt(messages, null);
    assertEquals(
        render("conversation-frame.md", conversation) + "\n\n" + resource("summarization.md"),
        initial);

    String update = CompactionPrompts.summaryUserPrompt(messages, "prior summary");
    assertEquals(
        render("conversation-frame.md", conversation)
            + "\n\n"
            + render("previous-summary-frame.md", "prior summary")
            + "\n\n"
            + resource("update-summarization.md"),
        update);
  }

  @Test
  void summaryUserPromptStripsRuntimeOwnedFileSectionsFromPreviousSummary() {
    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))));

    String prompt =
        CompactionPrompts.summaryUserPrompt(
            messages, "prior summary\n\n<read-files>\na.txt\n</read-files>");

    assertTrue(prompt.contains("prior summary"));
    assertTrue(!prompt.contains("<read-files>"));
    assertTrue(prompt.endsWith(resource("update-summarization.md")));
  }

  @Test
  void turnPrefixUserPromptFramesConversation() {
    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))));
    assertEquals(
        render("conversation-frame.md", CompactionPrompts.serializeConversation(messages))
            + "\n\n"
            + resource("turn-prefix.md"),
        CompactionPrompts.turnPrefixUserPrompt(messages));
  }

  @Test
  void compactedContextWrapsSummaryVerbatim() {
    assertEquals(
        render("compacted-context.md", "the summary"),
        CompactionPrompts.compactedContext("the summary"));
  }

  @Test
  void serializesTypedSectionsInPiOrder() {
    AgentMessage user =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("u")));
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ThinkingMessageContent("think"),
                new ToolCallMessageContent("call-1", "read", "read", "{\"path\":\"/a.txt\"}"),
                new TextMessageContent("answer")));
    AgentMessage tool =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    "read",
                    "read",
                    List.of(new TextMessageContent("file")),
                    false,
                    "{}")));

    String serialized = CompactionPrompts.serializeConversation(List.of(user, assistant, tool));

    assertEquals(
        "[User]: u\n\n[Assistant thinking]: think\n\n[Assistant]: answer\n\n"
            + "[Assistant tool calls]: read(path=\"/a.txt\")\n\n[Tool result]: file",
        serialized);
  }

  @Test
  void mediaAndResourcesUseDeterministicPlaceholders() {
    AgentMessage user =
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                new ImageMessageContent("image/png", "image-data"),
                new AudioMessageContent("audio/mp3", "audio-data"),
                new VideoMessageContent("video/mp4", "video-data"),
                ResourceMessageContent.media(new UUID(0L, 1L), "res.png")));
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new VideoMessageContent("video/webm", "assistant-video-data")));

    assertEquals(
        "[User]: [Image: image/png]\n[Audio: audio/mp3]\n[Video: video/mp4]\n"
            + "[Resource: res.png]\n\n[Assistant]: [Video: video/webm]",
        CompactionPrompts.serializeConversation(List.of(user, assistant)));
  }

  @Test
  void externalizedTextResourceUsesDeterministicPlaceholder() {
    AgentMessage user =
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                ResourceMessageContent.externalizedText(
                    new UUID(0L, 1L), "bash-result.txt", 1000L, 50L, "preview text")));

    assertEquals(
        "[User]: [Resource: bash-result.txt]",
        CompactionPrompts.serializeConversation(List.of(user)));
  }

  @Test
  void toolCallArgumentsFallBackToRawJsonForNonObjectInput() {
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ToolCallMessageContent("call-1", "bash", "bash", "[1,2,3]"),
                new TextMessageContent("x")));

    String serialized = CompactionPrompts.serializeConversation(List.of(assistant));

    assertTrue(serialized.contains("[Assistant tool calls]: bash(arguments=[1,2,3])"));
    assertTrue(serialized.contains("[Assistant]: x"));
  }

  @Test
  void toolResultIsTruncatedToTwoThousandChars() {
    String longText = "a".repeat(5_000);
    AgentMessage tool =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    "bash",
                    "bash",
                    List.of(new TextMessageContent(longText)),
                    false,
                    "{}")));

    String serialized = CompactionPrompts.serializeConversation(List.of(tool));

    assertTrue(serialized.startsWith("[Tool result]: "));
    assertTrue(serialized.contains("aaaa"));
    assertTrue(serialized.endsWith("[... 3000 more characters truncated]"));
    assertEquals(2_000, CompactionPrompts.TOOL_RESULT_MAX_CHARS);
  }

  @Test
  void emptyAndBlankContentProduceNoSections() {
    assertEquals("", CompactionPrompts.serializeConversation(List.of(AgentMessage.user(""))));
    assertEquals("", CompactionPrompts.serializeConversation(List.of()));
  }

  private static String render(String resourceName, String variableValue) {
    return LOADER
        .load(ROOT + resourceName)
        .render(Map.of(variableName(resourceName), variableValue));
  }

  private static String variableName(String resourceName) {
    return switch (resourceName) {
      case "conversation-frame.md" -> "conversation";
      case "previous-summary-frame.md" -> "previousSummary";
      case "compacted-context.md" -> "summary";
      default -> throw new IllegalArgumentException(resourceName);
    };
  }

  private static String resource(String resourceName) {
    return LOADER.load(ROOT + resourceName).raw();
  }
}
