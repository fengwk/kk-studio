package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;

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
   * 用最终 response 收敛累积内容：返回可持久化的 response（streamed thinking 在 final 缺失时补入）与需要补发布的尾部 gap deltas。任何
   * prefix 冲突、final 遗漏 streamed tool call 或 identity 冲突都抛 {@link IllegalArgumentException}。
   */
  Completion complete(ProviderResponse response) {
    List<ProviderStreamEvent> gaps = new ArrayList<>();
    String textGap = textGap(response.text());
    String thinkingGap = thinkingGap(response.thinking());
    if (partialToolCalls.keySet().stream()
        .anyMatch(index -> index >= response.toolCalls().size())) {
      throw new IllegalArgumentException("final response omits a streamed tool call");
    }
    List<ToolGap> toolGaps = new ArrayList<>(response.toolCalls().size());
    for (int index = 0; index < response.toolCalls().size(); index++) {
      ProviderToolCall complete = response.toolCalls().get(index);
      PartialToolCall partial = partialToolCalls.get(index);
      ToolGap gap =
          partial == null
              ? PartialToolCall.previewComplete(index, complete)
              : partial.previewGap(index, complete);
      toolGaps.add(gap);
    }
    if (textGap != null) {
      text.append(textGap);
      gaps.add(new ProviderStreamEvent.TextDelta(textGap));
    }
    if (thinkingGap != null) {
      thinking.append(thinkingGap);
      gaps.add(new ProviderStreamEvent.ThinkingDelta(thinkingGap));
    }
    for (int index = 0; index < toolGaps.size(); index++) {
      PartialToolCall partial =
          partialToolCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
      ProviderStreamEvent.ToolCallDelta gap = partial.applyGap(toolGaps.get(index));
      if (gap != null) {
        gaps.add(gap);
      }
    }
    ProviderResponse durableResponse = response;
    if (response.thinking().isEmpty() && thinking.length() > 0) {
      durableResponse = withThinking(response, thinking.toString());
    }
    return new Completion(durableResponse, List.copyOf(gaps));
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
        response.rawUsageJson());
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

    private ProviderStreamEvent.ToolCallDelta applyGap(ToolGap gap) {
      appendGap(id, gap.id());
      appendGap(name, gap.name());
      appendGap(arguments, gap.argumentsJson());
      if (gap.id() == null && gap.name() == null && gap.argumentsJson() == null) {
        return null;
      }
      return new ProviderStreamEvent.ToolCallDelta(
          gap.index(), gap.id(), gap.name(), gap.argumentsJson());
    }

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
      throw new IllegalArgumentException(
          "streamed tool call identity conflicts: " + current + " vs " + value);
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

  private record ToolGap(int index, String id, String name, String argumentsJson) {}
}
