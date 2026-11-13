package fun.fengwk.kkstudio.harness.builtin.request;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.NativeAssistantBlockContent;
import fun.fengwk.kkstudio.harness.api.session.RedactedThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultModelContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projects immutable history messages into provider-neutral messages without reclassifying old
 * tools.
 */
public final class ProviderMessageProjector {

  private static final String ORPHAN_RESULT_TEXT = "No result provided";

  public record ProjectedMessage(AgentMessage message, String reasoningEffort) {
    public ProjectedMessage {
      Objects.requireNonNull(message, "message");
      if (reasoningEffort != null && message.role() != AgentMessageRole.ASSISTANT) {
        throw new IllegalArgumentException(
            "reasoningEffort is only allowed for ASSISTANT messages");
      }
    }

    public static ProjectedMessage of(AgentMessage message) {
      return new ProjectedMessage(message, null);
    }
  }

  public ProviderMessageProjector() {}

  public List<ProviderMessage> project(List<AgentMessage> messages) {
    Objects.requireNonNull(messages, "messages");
    return projectSources(messages.stream().map(ProjectedMessage::of).toList());
  }

  public List<ProviderMessage> projectSources(List<ProjectedMessage> sources) {
    Objects.requireNonNull(sources, "sources");
    List<ProviderMessage> result = new ArrayList<>();
    Batch batch = new Batch();
    for (ProjectedMessage source : sources) {
      AgentMessage message = source.message();
      if (message.role() == AgentMessageRole.TOOL) {
        batch.addResult(projectToolResult((ToolResultMessageContent) message.contents().get(0)));
        continue;
      }
      batch.flush(result);
      if (message.role() == AgentMessageRole.ASSISTANT) {
        projectAssistant(message, source.reasoningEffort(), batch, result);
        continue;
      }
      result.add(
          new ProviderMessage(ProviderMessageRole.USER, projectContents(message.contents())));
    }
    batch.flush(result);
    return List.copyOf(result);
  }

  private void projectAssistant(
      AgentMessage message, String reasoningEffort, Batch batch, List<ProviderMessage> result) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof ToolCallMessageContent call) {
        ProviderToolCallBlock block = toolCallBlock(call);
        batch.openCalls.add(block);
        contents.add(block);
      } else {
        contents.add(projectContent(content));
      }
    }
    if (!contents.isEmpty()) {
      result.add(new ProviderMessage(ProviderMessageRole.ASSISTANT, contents, reasoningEffort));
    }
  }

  private ProviderToolResultBlock projectToolResult(ToolResultMessageContent content) {
    return new ProviderToolResultBlock(
        content.toolCallId(),
        content.toolName(),
        projectContents(ToolResultModelContents.contentsOf(content)),
        content.error(),
        content.detailsJson());
  }

  private static ProviderToolCallBlock toolCallBlock(ToolCallMessageContent call) {
    return new ProviderToolCallBlock(
        new ProviderToolCall(call.toolCallId(), call.toolName(), call.argumentsJson()),
        call.providerBlockData());
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
      return new ProviderTextBlock(value.text(), value.providerBlockData());
    }
    if (content instanceof ThinkingMessageContent value) {
      return new ProviderThinkingBlock(value.text(), value.providerBlockData());
    }
    if (content instanceof RedactedThinkingMessageContent value) {
      return new ProviderRedactedThinkingBlock(value.data(), value.providerBlockData());
    }
    if (content instanceof NativeAssistantBlockContent value) {
      return new ProviderOpaqueBlock(value.blockType(), value.providerBlockData());
    }
    if (content instanceof JsonMessageContent value) {
      return new ProviderJsonBlock(value.json());
    }
    if (content instanceof ToolCallMessageContent value) {
      return toolCallBlock(value);
    }
    if (content instanceof ToolResultMessageContent value) {
      return projectToolResult(value);
    }
    if (content instanceof ResourceMessageContent value) {
      return new ProviderResourceBlock(
          value.blobId(),
          value.name(),
          value.totalBytes(),
          value.totalLines(),
          value.preview() == null ? "" : value.preview(),
          value.imageTier(),
          value.originalWidth(),
          value.originalHeight(),
          value.displayWidth(),
          value.displayHeight());
    }
    throw new IllegalArgumentException("unsupported agent message content: " + content.getClass());
  }

  /**
   * Keeps historical tool results in their original order and closes only calls lacking results.
   */
  private static final class Batch {
    private final List<ProviderToolCallBlock> openCalls = new ArrayList<>();
    private final List<ProviderToolResultBlock> results = new ArrayList<>();

    private void addResult(ProviderToolResultBlock result) {
      openCalls.removeIf(open -> open.toolCall().id().equals(result.toolCallId()));
      results.add(result);
    }

    private void flush(List<ProviderMessage> output) {
      for (ProviderToolResultBlock result : results) {
        output.add(new ProviderMessage(ProviderMessageRole.TOOL, List.of(result)));
      }
      for (ProviderToolCallBlock call : openCalls) {
        ProviderToolResultBlock orphan =
            new ProviderToolResultBlock(
                call.toolCall().id(),
                call.toolCall().name(),
                List.of(new ProviderTextBlock(ORPHAN_RESULT_TEXT)),
                true,
                null);
        output.add(new ProviderMessage(ProviderMessageRole.TOOL, List.of(orphan)));
      }
      openCalls.clear();
      results.clear();
    }
  }
}
