package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulates one Provider stream and reconciles its terminal response with already published
 * deltas.
 *
 * <p>Only text and thinking are eligible for durable safe snapshots. Tool-call fragments remain
 * replayable SSE data only, but are still checked against the final response before it can become
 * durable.
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

  /** Returns the accumulated text + thinking safe snapshot without tool-call fragments. */
  SafeStreamSnapshot snapshotSafe(long sequence) {
    return new SafeStreamSnapshot(text.toString(), thinking.toString(), sequence);
  }

  Completion complete(ProviderResponse response) {
    List<ProviderStreamEvent> gaps = new ArrayList<>();
    appendTextGap(gaps, text, response.text());
    appendThinkingGap(gaps, thinking, response.thinking());
    if (partialToolCalls.keySet().stream()
        .anyMatch(index -> index >= response.toolCalls().size())) {
      throw new IllegalArgumentException("final response omits a streamed tool call");
    }
    for (int index = 0; index < response.toolCalls().size(); index++) {
      ProviderToolCall complete = response.toolCalls().get(index);
      ProviderStreamEvent.ToolCallDelta gap =
          partialToolCalls
              .computeIfAbsent(index, ignored -> new PartialToolCall())
              .gap(index, complete);
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

  private static boolean appendThinkingGap(
      List<ProviderStreamEvent> gaps, StringBuilder received, String complete) {
    String finalValue = complete == null ? "" : complete;
    String partial = received.toString();
    if (finalValue.isEmpty()) {
      return false;
    }
    if (!finalValue.startsWith(partial)) {
      throw new IllegalArgumentException("final response conflicts with streamed thinking");
    }
    if (finalValue.length() <= received.length()) {
      return false;
    }
    String gap = finalValue.substring(received.length());
    received.append(gap);
    gaps.add(new ProviderStreamEvent.ThinkingDelta(gap));
    return true;
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

  private static void appendTextGap(
      List<ProviderStreamEvent> gaps, StringBuilder received, String complete) {
    String finalValue = complete == null ? "" : complete;
    String partial = received.toString();
    if (!finalValue.startsWith(partial)) {
      throw new IllegalArgumentException("final response conflicts with streamed text");
    }
    if (finalValue.length() <= received.length()) {
      return;
    }
    String gap = finalValue.substring(received.length());
    received.append(gap);
    gaps.add(new ProviderStreamEvent.TextDelta(gap));
  }

  /** Holds the effective final response and the trailing SSE gaps to publish. */
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

    private ProviderStreamEvent.ToolCallDelta gap(int index, ProviderToolCall complete) {
      String idGap = gap(id, complete.id());
      String nameGap = gap(name, complete.name());
      String argumentsGap = gap(arguments, complete.argumentsJson());
      if (idGap == null && nameGap == null && argumentsGap == null) {
        return null;
      }
      return new ProviderStreamEvent.ToolCallDelta(index, idGap, nameGap, argumentsGap);
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

    private static String gap(StringBuilder received, String complete) {
      String partial = received.toString();
      if (!complete.startsWith(partial)) {
        throw new IllegalArgumentException("final tool call conflicts with streamed data");
      }
      if (complete.length() == partial.length()) {
        return null;
      }
      String gap = complete.substring(partial.length());
      received.append(gap);
      return gap;
    }
  }
}
