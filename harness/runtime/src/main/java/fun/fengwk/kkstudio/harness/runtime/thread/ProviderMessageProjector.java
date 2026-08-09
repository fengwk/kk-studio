package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 将语义 Context 投影为与 Provider SDK 无关的 ProviderMessage。
 *
 * <p>对当前连续 Tool chain 中未配对的 ToolCall，在角色切换或 Context 结束前合成 error ToolResult：{@code No result
 * provided}。Synthetic result 只存在于本次 Provider request，不写 Session Entry；后续重新出现相同 toolCallId 仍按新的
 * ToolCall 处理。
 */
public final class ProviderMessageProjector {
  private static final String ORPHAN_RESULT_TEXT = "No result provided";

  /** 非 data URI 的 prompt 标签最大字符数（含截断省略号），防止无限长 URI 注入 provider prompt。 */
  static final int MAX_URI_LABEL_CHARS = 512;

  public List<ProviderMessage> project(List<AgentMessage> messages) {
    List<ProviderMessage> result = new ArrayList<>();
    List<ProviderContentBlock> openToolCalls = new ArrayList<>();

    for (AgentMessage message : messages) {
      if (message.role() != AgentMessageRole.TOOL && !openToolCalls.isEmpty()) {
        flushOrphanToolResults(result, openToolCalls);
      }
      if (message.role() == AgentMessageRole.ASSISTANT) {
        List<ProviderContentBlock> contents = projectContents(message.contents());
        for (ProviderContentBlock block : contents) {
          if (block instanceof ProviderToolCallBlock toolCall) {
            openToolCalls.add(toolCall);
          }
        }
        result.add(new ProviderMessage(ProviderMessageRole.ASSISTANT, contents));
        continue;
      }
      if (message.role() == AgentMessageRole.TOOL) {
        List<ProviderContentBlock> contents = projectContents(message.contents());
        for (ProviderContentBlock block : contents) {
          if (block instanceof ProviderToolResultBlock toolResult) {
            openToolCalls.removeIf(
                open ->
                    open instanceof ProviderToolCallBlock call
                        && call.toolCall().id().equals(toolResult.toolCallId()));
          }
        }
        result.add(new ProviderMessage(ProviderMessageRole.TOOL, contents));
        continue;
      }
      result.add(
          new ProviderMessage(
              ProviderMessageRole.valueOf(message.role().name()),
              projectContents(message.contents())));
    }
    if (!openToolCalls.isEmpty()) {
      flushOrphanToolResults(result, openToolCalls);
    }
    return List.copyOf(result);
  }

  private void flushOrphanToolResults(
      List<ProviderMessage> result, List<ProviderContentBlock> openToolCalls) {
    List<ProviderContentBlock> synthetic = new ArrayList<>();
    for (ProviderContentBlock block : openToolCalls) {
      if (!(block instanceof ProviderToolCallBlock toolCall)) {
        continue;
      }
      String id = toolCall.toolCall().id();
      synthetic.add(
          new ProviderToolResultBlock(
              id,
              toolCall.toolCall().name(),
              List.of(new ProviderTextBlock(ORPHAN_RESULT_TEXT)),
              true,
              null));
    }
    openToolCalls.clear();
    if (!synthetic.isEmpty()) {
      result.add(new ProviderMessage(ProviderMessageRole.TOOL, List.copyOf(synthetic)));
    }
  }

  private List<ProviderContentBlock> projectContents(List<AgentMessageContent> contents) {
    List<ProviderContentBlock> result = new ArrayList<>(contents.size());
    for (AgentMessageContent content : contents) {
      result.add(projectContent(content));
    }
    return List.copyOf(result);
  }

  private ProviderContentBlock projectContent(AgentMessageContent content) {
    if (content instanceof TextMessageContent value) {
      return new ProviderTextBlock(value.text());
    }
    if (content instanceof ImageMessageContent value) {
      return new ProviderImageBlock(value.mediaType(), value.source());
    }
    if (content instanceof AudioMessageContent value) {
      return new ProviderAudioBlock(value.mediaType(), value.source());
    }
    if (content instanceof VideoMessageContent value) {
      return new ProviderVideoBlock(value.mediaType(), value.source());
    }
    if (content instanceof ThinkingMessageContent value) {
      return new ProviderThinkingBlock(value.text());
    }
    if (content instanceof JsonMessageContent value) {
      return new ProviderJsonBlock(value.json());
    }
    if (content instanceof ToolCallMessageContent value) {
      return new ProviderToolCallBlock(
          new ProviderToolCall(value.toolCallId(), value.toolName(), value.argumentsJson()));
    }
    if (content instanceof ToolResultMessageContent value) {
      return new ProviderToolResultBlock(
          value.toolCallId(),
          value.toolName(),
          projectContents(value.contents()),
          value.error(),
          value.detailsJson());
    }
    if (content instanceof ResourceMessageContent value) {
      ResourceRef resource = value.resource();
      String label = resourceLabel(resource);
      String preview = value.preview() == null ? "" : value.preview();
      return new ProviderTextBlock("[Resource " + label + "]\n" + preview);
    }
    throw new IllegalArgumentException("unsupported agent message content: " + content.getClass());
  }

  /**
   * Resource 的 prompt 标记：display name 优先；否则 data URI 用 {@code inline <mediaType>}（绝不把大 data URI 注入
   * prompt）；其余 URI 标签 ASCII 截断到至多 512 字符并追加 {@code ...}。preview 由 {@link ResourceMessageContent} 的
   * 16 KiB 上限约束。
   */
  private static String resourceLabel(ResourceRef resource) {
    if (resource.name() != null) {
      return resource.name();
    }
    String scheme = URI.create(resource.uri()).getScheme();
    if ("data".equals(scheme)) {
      return "inline " + resource.mediaType();
    }
    return truncateAscii(resource.uri());
  }

  /** ASCII URI 截断：超过 {@link #MAX_URI_LABEL_CHARS} 字符时保留前缀并追加 {@code ...}，总长不超过上限。 */
  private static String truncateAscii(String uri) {
    if (uri.length() <= MAX_URI_LABEL_CHARS) {
      return uri;
    }
    return uri.substring(0, MAX_URI_LABEL_CHARS - 3) + "...";
  }
}
