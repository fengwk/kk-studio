package fun.fengwk.kkstudio.harness.builtin.history;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.NativeAssistantBlockContent;
import fun.fengwk.kkstudio.harness.api.session.RedactedThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderToolCallDiagnosticJsonCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builtin 私有的 ProviderResponse 到公共历史 payload 投影。 */
public final class BuiltinHistoryPayloadMapper {

  private final ProviderToolCallDiagnosticJsonCodec diagnosticCodec =
      new ProviderToolCallDiagnosticJsonCodec();

  public MessagePayload assistantPayload(ProviderResponse response, List<ToolBinding> bindings) {
    return assistantPayload(response, bindings, null, null);
  }

  public MessagePayload assistantPayload(
      ProviderResponse response,
      List<ToolBinding> bindings,
      ModelSelection model,
      Instant requestedAt) {
    return assistantPayload(response, bindings, model, requestedAt, null);
  }

  public MessagePayload assistantPayload(
      ProviderResponse response,
      List<ToolBinding> bindings,
      ModelSelection model,
      Instant requestedAt,
      String effort) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(bindings, "bindings");
    List<AgentMessageContent> contents = new ArrayList<>();
    Map<Integer, ProviderToolCallDiagnostic> diagnosticByIndex = new HashMap<>();
    for (ProviderToolCallDiagnostic diagnostic : response.toolCallDiagnostics()) {
      diagnosticByIndex.put(diagnostic.callIndex(), diagnostic);
    }
    int outcomeIndex = 0;
    for (ProviderContentBlock block : response.blocks()) {
      if (block instanceof ProviderToolCallBlock callBlock) {
        while (diagnosticByIndex.containsKey(outcomeIndex)) {
          contents.add(
              new JsonMessageContent(
                  diagnosticCodec.encode(diagnosticByIndex.get(outcomeIndex++))));
        }
        ProviderToolCall call = callBlock.toolCall();
        ToolBinding binding = boundedBinding(call.name(), bindings);
        contents.add(
            new ToolCallMessageContent(
                call.id(),
                call.name(),
                binding == null
                    ? HistoryPayloadMapper.UNBOUND_RENDERER_KEY
                    : binding.descriptor().rendererKey(),
                call.argumentsJson(),
                call.historyAction(),
                callBlock.providerBlockData()));
        outcomeIndex++;
      } else if (block instanceof ProviderTextBlock textBlock) {
        contents.add(new TextMessageContent(textBlock.text(), textBlock.providerBlockData()));
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        contents.add(
            new ThinkingMessageContent(
                thinkingBlock.thinking(), thinkingBlock.providerBlockData()));
      } else if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
        contents.add(
            new RedactedThinkingMessageContent(
                redactedBlock.data(), redactedBlock.providerBlockData()));
      } else if (block instanceof ProviderOpaqueBlock opaqueBlock) {
        contents.add(
            new NativeAssistantBlockContent(
                opaqueBlock.blockType(), opaqueBlock.providerBlockData()));
      } else {
        throw new IllegalArgumentException(
            "unsupported provider response block: " + block.getClass());
      }
    }
    while (diagnosticByIndex.containsKey(outcomeIndex)) {
      contents.add(
          new JsonMessageContent(diagnosticCodec.encode(diagnosticByIndex.get(outcomeIndex++))));
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            response.stopReason(),
            response.usage(),
            response.decodeDurationMillis(),
            model,
            requestedAt,
            response.serviceTier(),
            response.requestBodyPricing(),
            effort),
        null);
  }

  public AssistantErrorPayload assistantErrorPayload(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    return new AssistantErrorPayload(
        new AssistantError(error.kind().name(), error.message()), null);
  }

  public AssistantErrorPayload assistantErrorPayload(
      ModelInvocationError error, ModelAttemptSnapshot attempt) {
    Objects.requireNonNull(error, "error");
    return new AssistantErrorPayload(
        new AssistantError(error.kind().name(), error.message()), attempt);
  }

  private static ToolBinding boundedBinding(String toolName, List<ToolBinding> bindings) {
    for (ToolBinding binding : bindings) {
      if (binding.descriptor().name().equals(toolName)) {
        return binding;
      }
    }
    return null;
  }
}
