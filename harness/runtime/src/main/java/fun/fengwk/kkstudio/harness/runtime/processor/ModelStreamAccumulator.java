package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 累积一次 Provider stream，并把 terminal response 与已发布 delta 严格 reconcile。
 *
 * <p>只有 text / thinking 有资格进入 durable {@code StreamCheckpoint}；tool-call fragment 只作为可重放的
 * projection delta 存在，但进入 durable 前仍必须与最终 response 校验（prefix / gap / 冲突即失败）。
 */
final class ModelStreamAccumulator {

  private final StringBuilder text = new StringBuilder();
  private final StringBuilder thinking = new StringBuilder();
  private final Map<Integer, PartialToolCall> partialToolCalls = new HashMap<>();

  void append(ProviderStreamEvent event) {
    Objects.requireNonNull(event, "event");
    if (event instanceof ProviderStreamEvent.TextDelta delta) {
      text.append(delta.text());
    } else if (event instanceof ProviderStreamEvent.ThinkingDelta delta) {
      thinking.append(delta.text());
    } else if (event instanceof ProviderStreamEvent.ToolCallDelta delta) {
      partialToolCalls
          .computeIfAbsent(delta.index(), ignored -> new PartialToolCall())
          .append(delta);
    }
  }

  /** 当前累积的 text（不含 tool-call fragment）。 */
  String text() {
    return text.toString();
  }

  /** 当前累积的 thinking（不含 tool-call fragment）。 */
  String thinking() {
    return thinking.toString();
  }

  /**
   * 预校验最终 response 并生成候选 durable response 与尾部 gap，在执行 {@link PreparedCompletion#apply()}
   * 之前绝不修改累积器状态。
   */
  PreparedCompletion prepareComplete(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    String textGap = textGap(response.text());
    String thinkingGap = thinkingGap(response.thinking());

    List<ToolGap> toolGaps = new ArrayList<>();
    if (response.stopReason() == GenerationStopReason.FILTERED) {
      if (!response.toolCalls().isEmpty() || !response.toolCallDiagnostics().isEmpty()) {
        throw new IllegalArgumentException(
            "FILTERED response must not contain tool calls or diagnostics");
      }
      // FILTERED 属于 provider protocol withdrawal：忽略已有 partialToolCalls，不生成 tool gap
    } else {
      int totalOutcomes = response.toolCalls().size() + response.toolCallDiagnostics().size();
      if (partialToolCalls.keySet().stream().anyMatch(index -> index >= totalOutcomes)) {
        throw new IllegalArgumentException("final response omits a streamed tool call");
      }
      Map<Integer, ProviderToolCallDiagnostic> diagnosticByIndex = new HashMap<>();
      for (ProviderToolCallDiagnostic diagnostic : response.toolCallDiagnostics()) {
        diagnosticByIndex.put(diagnostic.callIndex(), diagnostic);
      }
      int completeCallIndex = 0;
      for (int index = 0; index < totalOutcomes; index++) {
        ProviderToolCallDiagnostic diagnostic = diagnosticByIndex.get(index);
        if (diagnostic != null) {
          PartialToolCall partial = partialToolCalls.get(index);
          if (partial != null) {
            partial.reconcileDiagnostic(diagnostic);
          }
        } else {
          ProviderToolCall complete = response.toolCalls().get(completeCallIndex++);
          PartialToolCall partial = partialToolCalls.get(index);
          ToolGap gap =
              partial == null
                  ? PartialToolCall.previewComplete(index, complete)
                  : partial.previewGap(index, complete);
          toolGaps.add(gap);
        }
      }
    }
    List<ProviderStreamEvent> gaps = new ArrayList<>();
    if (textGap != null) {
      gaps.add(new ProviderStreamEvent.TextDelta(textGap));
    }
    if (thinkingGap != null) {
      gaps.add(new ProviderStreamEvent.ThinkingDelta(thinkingGap));
    }
    for (ToolGap gap : toolGaps) {
      ProviderStreamEvent.ToolCallDelta delta = gap.toDelta();
      if (delta != null) {
        gaps.add(delta);
      }
    }
    String candidateThinking = response.thinking();
    if (candidateThinking.isEmpty()) {
      StringBuilder combined = new StringBuilder(thinking);
      if (thinkingGap != null) {
        combined.append(thinkingGap);
      }
      if (combined.length() > 0) {
        candidateThinking = combined.toString();
      }
    }
    ProviderResponse durableResponse = response;
    if (!candidateThinking.equals(response.thinking())) {
      durableResponse = withThinking(response, candidateThinking);
    }
    final ProviderResponse finalDurableResponse = durableResponse;
    final List<ProviderStreamEvent> finalGaps = List.copyOf(gaps);
    return new PreparedCompletion(
        finalDurableResponse,
        finalGaps,
        () -> {
          if (textGap != null) {
            text.append(textGap);
          }
          if (thinkingGap != null) {
            thinking.append(thinkingGap);
          }
          for (ToolGap gap : toolGaps) {
            PartialToolCall partial =
                partialToolCalls.computeIfAbsent(gap.index(), ignored -> new PartialToolCall());
            partial.applyGap(gap);
          }
        });
  }

  /**
   * 用最终 response 收敛累积内容：返回可持久化的 response（streamed thinking 在 final 缺失时补入）与需要补发布的尾部 gap deltas。任何
   * prefix 冲突、final 遗漏 streamed tool call 或 identity 冲突都抛 {@link IllegalArgumentException}。
   */
  Completion complete(ProviderResponse response) {
    return prepareComplete(response).apply();
  }

  private static ProviderResponse withThinking(ProviderResponse response, String thinking) {
    return new ProviderResponse(
        response.text(),
        thinking,
        response.toolCalls(),
        response.stopReason(),
        response.usage(),
        response.cost(),
        response.requestId(),
        response.serviceTier(),
        response.rawUsageJson(),
        response.toolCallDiagnostics());
  }

  private String textGap(String complete) {
    String finalValue = complete == null ? "" : complete;
    String partial = text.toString();
    if (!finalValue.startsWith(partial)) {
      throw new IllegalArgumentException("final response conflicts with streamed text");
    }
    return finalValue.length() == partial.length() ? null : finalValue.substring(partial.length());
  }

  private String thinkingGap(String complete) {
    String finalValue = complete == null ? "" : complete;
    String partial = thinking.toString();
    if (finalValue.isEmpty()) {
      return null;
    }
    if (!finalValue.startsWith(partial)) {
      throw new IllegalArgumentException("final response conflicts with streamed thinking");
    }
    return finalValue.length() == partial.length() ? null : finalValue.substring(partial.length());
  }

  /** 生效的最终 response 与需要补发布的尾部 SSE gaps。 */
  record Completion(ProviderResponse response, List<ProviderStreamEvent> gaps) {
    Completion {
      response = Objects.requireNonNull(response, "response");
      gaps = List.copyOf(gaps);
    }
  }

  /** 预处理完成的 completion 对象：携带校验后的 response 与 gaps，仅在调用 {@link #apply()} 时修改累积器状态。 */
  static final class PreparedCompletion {
    private final ProviderResponse response;
    private final List<ProviderStreamEvent> gaps;
    private final Runnable applyAction;
    private boolean applied;

    PreparedCompletion(
        ProviderResponse response, List<ProviderStreamEvent> gaps, Runnable applyAction) {
      this.response = Objects.requireNonNull(response, "response");
      this.gaps = List.copyOf(gaps);
      this.applyAction = Objects.requireNonNull(applyAction, "applyAction");
    }

    ProviderResponse response() {
      return response;
    }

    List<ProviderStreamEvent> gaps() {
      return gaps;
    }

    Completion apply() {
      if (applied) {
        throw new IllegalStateException("prepared completion already applied");
      }
      applied = true;
      applyAction.run();
      return new Completion(response, gaps);
    }
  }

  private static final class PartialToolCall {
    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    private void append(ProviderStreamEvent.ToolCallDelta delta) {
      appendIdentity(id, delta.id());
      appendIdentity(name, delta.name());
      if (delta.argumentsJson() != null) {
        arguments.append(delta.argumentsJson());
      }
    }

    private ToolGap previewGap(int index, ProviderToolCall complete) {
      return new ToolGap(
          index,
          previewGap(id, complete.id()),
          previewGap(name, complete.name()),
          previewGap(arguments, complete.argumentsJson()));
    }

    private static ToolGap previewComplete(int index, ProviderToolCall complete) {
      return new ToolGap(index, complete.id(), complete.name(), complete.argumentsJson());
    }

    private void applyGap(ToolGap gap) {
      appendGap(id, gap.id());
      appendGap(name, gap.name());
      appendGap(arguments, gap.argumentsJson());
    }

    private void reconcileDiagnostic(ProviderToolCallDiagnostic diagnostic) {
      String streamedId = id.toString();
      if (!streamedId.isEmpty() && !streamedId.equals(diagnostic.id())) {
        throw new IllegalArgumentException("streamed tool call id conflicts with diagnostic");
      }
      String streamedName = name.toString();
      if (!streamedName.isEmpty() && !streamedName.equals(diagnostic.name())) {
        throw new IllegalArgumentException("streamed tool call name conflicts with diagnostic");
      }
      String streamedArgs = arguments.toString();
      if (!streamedArgs.equals(diagnostic.partialArguments())) {
        throw new IllegalArgumentException("streamed tool call arguments conflict with diagnostic");
      }
    }

    /** 接受首次值或当前值的前缀扩展，忽略重复及已接收前缀，并拒绝相互冲突的片段。 */
    private static void appendIdentity(StringBuilder target, String value) {
      if (value == null || value.isBlank()) {
        return;
      }
      if (target.length() == 0) {
        target.append(value);
        return;
      }
      String current = target.toString();
      if (value.equals(current) || current.startsWith(value)) {
        return;
      }
      if (value.startsWith(current)) {
        target.append(value, current.length(), value.length());
        return;
      }
      throw new IllegalArgumentException("streamed tool call identity conflict");
    }

    private static String previewGap(StringBuilder received, String complete) {
      String partial = received.toString();
      if (!complete.startsWith(partial)) {
        throw new IllegalArgumentException("final tool call conflicts with streamed data");
      }
      return complete.length() == partial.length() ? null : complete.substring(partial.length());
    }

    private static void appendGap(StringBuilder received, String gap) {
      if (gap != null) {
        received.append(gap);
      }
    }
  }

  private record ToolGap(int index, String id, String name, String argumentsJson) {
    ProviderStreamEvent.ToolCallDelta toDelta() {
      if (id == null && name == null && argumentsJson == null) {
        return null;
      }
      return new ProviderStreamEvent.ToolCallDelta(index, id, name, argumentsJson);
    }
  }
}
