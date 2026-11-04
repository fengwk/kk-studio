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
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
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
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(bindings, "bindings");
    List<AgentMessageContent> contents = new ArrayList<>();
    if (!response.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(response.thinking()));
    }
    if (!response.text().isEmpty()) {
      contents.add(new TextMessageContent(response.text()));
    }
    int totalOutcomes = response.toolCalls().size() + response.toolCallDiagnostics().size();
    Map<Integer, ProviderToolCallDiagnostic> diagnosticByIndex = new HashMap<>();
    for (ProviderToolCallDiagnostic diagnostic : response.toolCallDiagnostics()) {
      diagnosticByIndex.put(diagnostic.callIndex(), diagnostic);
    }
    int toolCallIndex = 0;
    for (int i = 0; i < totalOutcomes; i++) {
      ProviderToolCallDiagnostic diagnostic = diagnosticByIndex.get(i);
      if (diagnostic != null) {
        contents.add(new JsonMessageContent(diagnosticCodec.encode(diagnostic)));
      } else {
        ProviderToolCall call = response.toolCalls().get(toolCallIndex++);
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
                binding == null ? null : binding.environmentName()));
      }
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
            response.requestBodyPricing()),
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
