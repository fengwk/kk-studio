package fun.fengwk.kkstudio.harness.runtime.compaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplate;
import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplateLoader;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 压缩 prompt 的 classpath 资源入口与 Pi 风格对话序列化。
 *
 * <p>prompt 文本逐字复制自上游 Pi（见同目录 NOTICE），所有注入变量（conversation / previousSummary / summary）一律通过 严格
 * {@link PromptTemplate} 渲染。序列化格式对齐 Pi：{@code [User]} / {@code [Assistant thinking]} / {@code
 * [Assistant]} / {@code [Assistant tool calls]} / {@code [Tool result]}；每个 Tool result 截断到 2_000
 * 字符；非文本媒体 / 资源使用确定性占位符（如 {@code [Image: image/png]}）而不是丢失。
 */
public final class CompactionPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/runtime/compaction/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 序列化 Tool result 的字符上限。 */
  static final int TOOL_RESULT_MAX_CHARS = 2_000;

  private CompactionPrompts() {}

  /** 压缩 Model 调用的 system prompt（无变量）。 */
  public static String summarizationSystemPrompt() {
    return text("summarization-system.md");
  }

  /**
   * FULL/HISTORY 的 user prompt：conversation frame + （有 previous summary 时）previous-summary frame +
   * initial/update summarization prompt。
   */
  public static String summaryUserPrompt(List<AgentMessage> messages, String previousSummary) {
    String canonicalPreviousSummary =
        previousSummary == null
            ? null
            : CompactionFileSections.stripReservedSections(previousSummary);
    if (canonicalPreviousSummary != null && canonicalPreviousSummary.isBlank()) {
      canonicalPreviousSummary = null;
    }
    StringBuilder prompt = new StringBuilder();
    prompt.append(
        render("conversation-frame.md", Map.of("conversation", serializeConversation(messages))));
    if (canonicalPreviousSummary != null) {
      prompt
          .append("\n\n")
          .append(
              render(
                  "previous-summary-frame.md",
                  Map.of("previousSummary", canonicalPreviousSummary)));
    }
    prompt.append("\n\n");
    prompt.append(
        canonicalPreviousSummary == null
            ? text("summarization.md")
            : text("update-summarization.md"));
    return prompt.toString();
  }

  /** TURN_PREFIX 的 user prompt：conversation frame + turn-prefix prompt。 */
  public static String turnPrefixUserPrompt(List<AgentMessage> messages) {
    return render("conversation-frame.md", Map.of("conversation", serializeConversation(messages)))
        + "\n\n"
        + text("turn-prefix.md");
  }

  /** 后续正常上下文把 complete 压缩 summary 投影为 USER 消息的 Pi wrapper。 */
  public static String compactedContext(String summary) {
    return render("compacted-context.md", Map.of("summary", summary));
  }

  /** Pi 风格对话序列化：类型化 section + 确定性媒体占位符 + Tool result 2_000 字符截断。 */
  public static String serializeConversation(List<AgentMessage> messages) {
    List<String> parts = new ArrayList<>();
    for (AgentMessage message : messages) {
      switch (message.role()) {
        case USER -> {
          String content = contentText(message.contents());
          if (!content.isEmpty()) {
            parts.add("[User]: " + content);
          }
        }
        case ASSISTANT -> {
          List<String> thinking = new ArrayList<>();
          List<String> texts = new ArrayList<>();
          List<String> toolCalls = new ArrayList<>();
          for (AgentMessageContent content : message.contents()) {
            if (content instanceof ThinkingMessageContent thinkingBlock) {
              thinking.add(thinkingBlock.text());
            } else if (content instanceof ToolCallMessageContent call) {
              toolCalls.add(formatToolCall(call));
            } else if (content instanceof TextMessageContent text) {
              texts.add(text.text());
            } else if (content instanceof JsonMessageContent json) {
              texts.add(json.json());
            } else if (content instanceof ImageMessageContent image) {
              texts.add("[Image: " + image.mediaType() + "]");
            } else if (content instanceof AudioMessageContent audio) {
              texts.add("[Audio: " + audio.mediaType() + "]");
            } else if (content instanceof VideoMessageContent video) {
              texts.add("[Video: " + video.mediaType() + "]");
            } else if (content instanceof ResourceMessageContent resource) {
              texts.add(resourcePlaceholder(resource));
            }
          }
          if (!thinking.isEmpty()) {
            parts.add("[Assistant thinking]: " + String.join("\n", thinking));
          }
          if (!texts.isEmpty()) {
            parts.add("[Assistant]: " + String.join("\n", texts));
          }
          if (!toolCalls.isEmpty()) {
            parts.add("[Assistant tool calls]: " + String.join("; ", toolCalls));
          }
        }
        case TOOL -> {
          AgentMessageContent content = message.contents().get(0);
          if (content instanceof ToolResultMessageContent result) {
            String text = contentText(result.contents());
            if (!text.isEmpty()) {
              parts.add("[Tool result]: " + truncateForSummary(text, TOOL_RESULT_MAX_CHARS));
            }
          }
        }
        case SYSTEM -> {
          // summarization 输入不包含 SYSTEM 消息。
        }
      }
    }
    return String.join("\n\n", parts);
  }

  /** tool call 的 Pi 风格渲染：{@code name(k=v, ...)}，arguments 按 JSON 原样序列化。 */
  private static String formatToolCall(ToolCallMessageContent call) {
    List<String> arguments = new ArrayList<>();
    JsonNode node;
    try {
      node = MAPPER.readTree(call.argumentsJson());
    } catch (JsonProcessingException error) {
      node = null;
    }
    if (node != null && node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        arguments.add(field.getKey() + "=" + field.getValue().toString());
      }
    } else {
      arguments.add("arguments=" + call.argumentsJson());
    }
    return call.toolName() + "(" + String.join(", ", arguments) + ")";
  }

  private static String resourcePlaceholder(ResourceMessageContent resource) {
    String label = resource.name();
    if (label == null || label.isBlank()) {
      label = resource.blobId().toString();
    }
    return "[Resource: " + label + "]";
  }

  private static String contentText(List<AgentMessageContent> contents) {
    List<String> parts = new ArrayList<>();
    for (AgentMessageContent content : contents) {
      if (content instanceof TextMessageContent text) {
        parts.add(text.text());
      } else if (content instanceof JsonMessageContent json) {
        parts.add(json.json());
      } else if (content instanceof ImageMessageContent image) {
        parts.add("[Image: " + image.mediaType() + "]");
      } else if (content instanceof AudioMessageContent audio) {
        parts.add("[Audio: " + audio.mediaType() + "]");
      } else if (content instanceof VideoMessageContent video) {
        parts.add("[Video: " + video.mediaType() + "]");
      } else if (content instanceof ResourceMessageContent resource) {
        parts.add(resourcePlaceholder(resource));
      }
    }
    return String.join("\n", parts);
  }

  private static String truncateForSummary(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    int truncatedChars = text.length() - maxChars;
    return text.substring(0, maxChars)
        + "\n\n[... "
        + truncatedChars
        + " more characters truncated]";
  }

  private static PromptTemplate template(String name) {
    return LOADER.load(ROOT + name);
  }

  private static String text(String name) {
    return template(name).render(Map.of());
  }

  private static String render(String name, Map<String, String> values) {
    return template(name).render(values);
  }
}
