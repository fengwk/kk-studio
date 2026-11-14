package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验 OpenAI Chat assistant replay payload 的结构、已知字段类型与 durable 一致性。
 *
 * <p>损坏或与 durable 矛盾一律 {@code INVALID_REQUEST}；错误文案绝不回显 payload 内容。
 */
final class OpenAiChatReplayValidator {

  private OpenAiChatReplayValidator() {}

  static void validate(ObjectNode payload, List<ProviderContentBlock> durableContents) {
    // role 由 replay builder 固定为 assistant，payload 本身必为对象，故此处只校验原生字段。
    rejectResponseOnlyFields(payload);
    requireNullableString(
        payload, "content", "invalid OpenAI chat assistant replay payload: illegal content type");
    requireNullableString(
        payload, "refusal", "invalid OpenAI chat assistant replay payload: illegal refusal type");
    requireNullableString(
        payload,
        "reasoning_content",
        "invalid OpenAI chat assistant replay payload: illegal reasoning_content type");
    requireReasoningDetails(payload);

    List<ProviderToolCall> payloadCalls = readReplayToolCalls(payload);
    DurableContents durable = readDurableContents(durableContents);

    requireTextMatches(payload, durable);
    requireThinkingMatches(payload, durable);
    requireToolCallsMatch(payloadCalls, durable.toolCalls());
  }

  /** 响应数据不能作为下一轮的请求字段；audio 只接受请求侧的 id 引用。 */
  private static void rejectResponseOnlyFields(JsonNode payload) {
    if (payload.has("annotations")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid OpenAI chat assistant replay annotations");
    }
    if (!payload.has("audio")) {
      return;
    }
    JsonNode audio = payload.get("audio");
    if (!audio.isObject()
        || audio.size() != 1
        || !audio.path("id").isString()
        || audio.path("id").stringValue().isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid OpenAI chat assistant replay audio");
    }
  }

  private static void requireNullableString(JsonNode payload, String field, String errorMessage) {
    if (!payload.has(field)) {
      return;
    }
    JsonNode value = payload.get(field);
    if (!value.isNull() && !value.isString()) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
  }

  private static void requireReasoningDetails(JsonNode payload) {
    if (!payload.has("reasoning_details")) {
      return;
    }
    JsonNode details = payload.get("reasoning_details");
    if (!details.isNull() && !details.isObject() && !details.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: illegal reasoning_details type");
    }
  }

  /** 读取 payload 中可参与 durable 比对的 function 调用；custom/未知调用类型保留为原生事实但不参与比对。 */
  private static List<ProviderToolCall> readReplayToolCalls(JsonNode payload) {
    List<ProviderToolCall> calls = new ArrayList<>();
    if (!payload.has("tool_calls") || payload.get("tool_calls").isNull()) {
      return calls;
    }
    JsonNode toolCalls = payload.get("tool_calls");
    if (!toolCalls.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: illegal tool_calls type");
    }
    for (JsonNode callNode : toolCalls) {
      ProviderToolCall call = readReplayToolCall(callNode);
      if (call != null) {
        calls.add(call);
      }
    }
    return calls;
  }

  private static ProviderToolCall readReplayToolCall(JsonNode callNode) {
    if (!callNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: tool call must be a JSON object");
    }
    if (callNode.has("index")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay tool call index");
    }
    // 现代 function 调用要求 id/type/function{name,arguments} 完整且合法；额外嵌套字段属于原生事实可保留
    if (!isNonBlankString(callNode.get("id"))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: tool call id must be a non-blank string");
    }
    if (!callNode.has("type") || !callNode.get("type").isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: tool call type must be function");
    }
    if (!"function".equals(callNode.get("type").stringValue())) {
      return null;
    }
    JsonNode function = callNode.get("function");
    if (function == null || !function.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: tool call function must be a JSON object");
    }
    if (!isNonBlankString(function.get("name"))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: tool call function name must be a non-blank string");
    }
    if (!isNonBlankString(function.get("arguments"))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: tool call function arguments must be a non-blank string");
    }
    String argumentsJson = function.get("arguments").stringValue();
    ProviderJsonHelper.parseStrictJsonObject(
        argumentsJson,
        "invalid OpenAI chat assistant replay payload: tool call function arguments must be a JSON object");
    return new ProviderToolCall(
        callNode.get("id").stringValue(), function.get("name").stringValue(), argumentsJson);
  }

  private static DurableContents readDurableContents(List<ProviderContentBlock> blocks) {
    StringBuilder text = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    List<ProviderToolCall> toolCalls = new ArrayList<>();
    for (ProviderContentBlock block : blocks) {
      if (block instanceof ProviderTextBlock textBlock) {
        text.append(textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        thinking.append(thinkingBlock.thinking());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        ProviderJsonHelper.parseStrictJsonObject(
            call.argumentsJson(),
            "invalid OpenAI chat assistant durable tool call arguments: must be a JSON object");
        toolCalls.add(call);
      }
    }
    return new DurableContents(text.toString(), thinking.toString(), toolCalls);
  }

  private static void requireTextMatches(JsonNode payload, DurableContents durable) {
    StringBuilder payloadText = new StringBuilder();
    if (payload.has("content") && payload.get("content").isString()) {
      payloadText.append(payload.get("content").stringValue());
    }
    if (payload.has("refusal") && payload.get("refusal").isString()) {
      payloadText.append(payload.get("refusal").stringValue());
    }
    if (!payloadText.toString().equals(durable.text())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    }
  }

  private static void requireThinkingMatches(JsonNode payload, DurableContents durable) {
    String payloadThinking =
        (payload.has("reasoning_content") && payload.get("reasoning_content").isString())
            ? payload.get("reasoning_content").stringValue()
            : "";
    if (!payloadThinking.equals(durable.thinking())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: reasoning_content mismatch with durable contents");
    }
  }

  private static void requireToolCallsMatch(
      List<ProviderToolCall> payloadCalls, List<ProviderToolCall> durableCalls) {
    if (payloadCalls.size() != durableCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    }
    for (int i = 0; i < payloadCalls.size(); i++) {
      ProviderToolCall payloadCall = payloadCalls.get(i);
      ProviderToolCall durableCall = durableCalls.get(i);
      if (!payloadCall.id().equals(durableCall.id())
          || !payloadCall.name().equals(durableCall.name())
          || !payloadCall.argumentsJson().equals(durableCall.argumentsJson())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "invalid OpenAI chat assistant replay payload: mismatch with durable contents");
      }
    }
  }

  private static boolean isNonBlankString(JsonNode node) {
    return node != null && node.isString() && !node.stringValue().isBlank();
  }

  private record DurableContents(String text, String thinking, List<ProviderToolCall> toolCalls) {}
}
