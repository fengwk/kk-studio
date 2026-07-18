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
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.ArrayList;
import java.util.List;

/** 将语义 Context 无 Provider SDK 绑定地投影为 ProviderMessage。 */
public final class ProviderMessageProjector {

  public List<ProviderMessage> project(List<AgentMessage> messages) {
    return messages.stream().map(this::project).toList();
  }

  private ProviderMessage project(AgentMessage message) {
    return new ProviderMessage(
        ProviderMessageRole.valueOf(message.role().name()), projectContents(message.contents()));
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
