package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将语义 Context 与 Provider SDK 绑定地投影为 ProviderMessage。
 *
 * <p>对 orphan ToolCall 在角色切换或 Context 结束前合成 error ToolResult：{@code No result provided}。 Synthetic
 * result 只存在于 Provider request，不写 Session Entry。
 */
public final class ProviderMessageProjector {
  private static final String ORPHAN_RESULT_TEXT = "No result provided";

  public List<ProviderMessage> project(List<AgentMessage> messages) {
    List<ProviderMessage> result = new ArrayList<>();
    List<ProviderContentBlock> openToolCalls = new ArrayList<>();
    Set<String> resolvedToolCallIds = new HashSet<>();

    for (AgentMessage message : messages) {
      if (message.role() != AgentMessageRole.TOOL && !openToolCalls.isEmpty()) {
        flushOrphanToolResults(result, openToolCalls, resolvedToolCallIds);
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
            resolvedToolCallIds.add(toolResult.toolCallId());
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
      flushOrphanToolResults(result, openToolCalls, resolvedToolCallIds);
    }
    return List.copyOf(result);
  }

  private void flushOrphanToolResults(
      List<ProviderMessage> result,
      List<ProviderContentBlock> openToolCalls,
      Set<String> resolvedToolCallIds) {
    List<ProviderContentBlock> synthetic = new ArrayList<>();
    for (ProviderContentBlock block : openToolCalls) {
      if (!(block instanceof ProviderToolCallBlock toolCall)) {
        continue;
      }
      String id = toolCall.toolCall().id();
      if (resolvedToolCallIds.contains(id)) {
        continue;
      }
      synthetic.add(
          new ProviderToolResultBlock(
              id,
              toolCall.toolCall().name(),
              List.of(new ProviderTextBlock(ORPHAN_RESULT_TEXT)),
              true,
              null));
      resolvedToolCallIds.add(id);
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
    if (content instanceof ArtifactMessageContent value) {
      String preview = value.preview() == null ? "" : value.preview();
      return new ProviderTextBlock(
          "[Artifact " + value.artifactId() + " (" + value.mediaType() + ")]\n" + preview);
    }
    throw new IllegalArgumentException("unsupported agent message content: " + content.getClass());
  }
}
