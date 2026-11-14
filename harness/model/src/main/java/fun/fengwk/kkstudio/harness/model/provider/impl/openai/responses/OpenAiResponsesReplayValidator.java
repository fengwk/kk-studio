package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

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
import java.util.Set;

/**
 * 严格校验 Responses replay payload 的真实形状与 durable 语义一致性。
 *
 * <p>结构性损坏或与 durable 文本/思考/工具调用矛盾都在此抛出 {@code INVALID_REQUEST}，与 affinity 是否匹配无关。已知 {@code
 * message}/{@code reasoning}/{@code function_call} 只对 durable 语义所必需的字段做结构/类型校验并逐项比对 durable 内容；超出
 * durable 语义的官方响应字段（{@code phase}、{@code annotations}/{@code logprobs}、reasoning 密文、 {@code status}
 * 及未来新增成员）与未知官方 item 都作为原生事实原样保留，不影响校验结果。
 */
final class OpenAiResponsesReplayValidator {

  /** 可等价重建的 reasoning item 成员：超出这些成员的额外成员是 durable 语义无法表达的原生事实。 */
  private static final Set<String> REASONING_ITEM_FIELDS =
      Set.of("type", "id", "status", "summary");

  private OpenAiResponsesReplayValidator() {}

  /**
   * 校验 payload 并返回该 payload 是否只由空 reasoning 占位符构成。
   *
   * <p>无 {@code encrypted_content}、无可用摘要文本、也没有超出 {@code type/id/status/summary} 成员的 reasoning item
   * 是上游“原生推理不可用”的合法空占位符：当 payload 只由这类占位符构成时不承载原生推理，返回 {@code true} 由调用方回退语义编码， 而不是判为损坏请求。
   *
   * <p>带 {@code encrypted_content} 且没有可比可读文本的 reasoning item 是原生推理的权威事实：即便 durable 有可读思考也不
   * 判为矛盾（该可读文本无法在 payload 内表达），放行原位回放；真实可读文本矛盾或纯文本占位符仍需回退或拒绝。
   */
  static boolean isEmptyPlaceholderOnly(
      ArrayNode outputArray, List<ProviderContentBlock> durableContents) {
    ReplayFacts replay = collectReplayFacts(outputArray);
    DurableFacts durable = collectDurableFacts(durableContents);
    requireTextMatches(replay, durable);
    requireThinkingMatches(replay, durable);
    requireToolCallsMatch(replay.toolCalls, durable.toolCalls);
    return replay.isEmptyPlaceholderOnly();
  }

  private static ReplayFacts collectReplayFacts(ArrayNode outputArray) {
    ReplayFacts facts = new ReplayFacts();
    for (JsonNode item : outputArray) {
      String type = item.get("type").stringValue();
      switch (type) {
        case "message" -> collectMessageFacts(facts, item);
        case "reasoning" -> collectReasoningFacts(facts, item);
        case "function_call" -> collectFunctionCallFacts(facts, item);
        default -> {
          // 未知但可信的官方 item（web/file search、code interpreter、image generation、custom tool
          // call、computer/shell 等）：不承载 durable 语义，因此不参与文本/思考/工具一致性比对，只作为原生事实原样回放。
        }
      }
    }
    return facts;
  }

  private static void collectMessageFacts(ReplayFacts facts, JsonNode item) {
    // id/status/phase 与任何额外成员都是原生事实，原样保留；role 与 content 形状由 replay builder 固定。
    requireOptionalNonBlankString(item, "id", "replay message id must be non-blank string");
    requireOptionalNonBlankString(item, "phase", "replay message phase must be non-blank string");
    for (JsonNode block : item.get("content")) {
      collectMessageContentBlock(facts, block);
    }
  }

  private static void collectMessageContentBlock(ReplayFacts facts, JsonNode block) {
    if (!block.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "replay message content block must be an object");
    }
    if (!block.has("type") || !block.get("type").isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "replay message content block must have string type");
    }
    switch (block.get("type").stringValue()) {
      case "output_text" -> {
        if (!block.has("text") || !block.get("text").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "replay output_text block must have string text");
        }
        // annotations/logprobs 与任何额外成员都是原生事实，原样保留
        facts.text.append(block.get("text").stringValue());
      }
      case "refusal" -> {
        if (!block.has("refusal") || !block.get("refusal").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "replay refusal block must have string refusal");
        }
        facts.text.append(block.get("refusal").stringValue());
      }
      default -> {
        // Unknown content unions are opaque native facts and do not contribute text.
      }
    }
  }

  private static void collectReasoningFacts(ReplayFacts facts, JsonNode item) {
    requireOptionalNonBlankString(item, "id", "replay reasoning id must be non-blank string");
    boolean hasEncrypted = false;
    if (item.has("encrypted_content")) {
      JsonNode encrypted = item.get("encrypted_content");
      if (!encrypted.isString() || encrypted.stringValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay reasoning encrypted_content must be non-blank string");
      }
      hasEncrypted = true;
    }
    boolean hasUsableSummary = hasUsableSummary(item);
    // content 形状严格校验与可比文本读取复用同一 helper；损坏历史一律 INVALID_REQUEST。
    facts.thinking.append(
        OpenAiResponsesReasoningText.read(item, ProviderErrorKind.INVALID_REQUEST));
    facts.reasoningItemCount++;
    if (hasEncrypted) {
      facts.hasOpaqueReasoning = true;
    }
    if (!hasEncrypted && !hasFieldOutside(item, REASONING_ITEM_FIELDS) && !hasUsableSummary) {
      // 无密文、无可用摘要文本、无额外成员：结构合法的空占位符（上游声明原生推理不可用），而不是损坏请求。
      facts.emptyReasoningPlaceholderCount++;
    }
  }

  private static boolean hasUsableSummary(JsonNode item) {
    boolean usable = false;
    for (JsonNode part : item.get("summary")) {
      if (!part.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "replay reasoning summary block must be an object");
      }
      if (!part.has("type")
          || !part.get("type").isString()
          || !"summary_text".equals(part.get("type").stringValue())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay reasoning summary block type must be 'summary_text'");
      }
      if (!part.has("text") || !part.get("text").isString()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay reasoning summary block must have string text");
      }
      // opaque summary 块（除 type/text 外还有成员）仍按原生事实原样保留
      if (!part.get("text").stringValue().isBlank()) {
        usable = true;
      }
    }
    return usable;
  }

  private static void collectFunctionCallFacts(ReplayFacts facts, JsonNode item) {
    String callId = null;
    if (item.has("call_id")) {
      if (!item.get("call_id").isString() || item.get("call_id").stringValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "function_call call_id must be non-blank string");
      }
      callId = item.get("call_id").stringValue();
    }
    if (item.has("id")) {
      if (!item.get("id").isString() || item.get("id").stringValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "function_call id must be non-blank string");
      }
      if (callId == null) {
        callId = item.get("id").stringValue();
      }
    }
    if (callId == null || callId.isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "function_call missing call_id or id");
    }
    if (!item.has("name")
        || !item.get("name").isString()
        || item.get("name").stringValue().isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "function_call missing non-blank string name");
    }
    if (!item.has("arguments") || !item.get("arguments").isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "function_call missing string arguments");
    }
    String arguments = item.get("arguments").stringValue();
    ProviderJsonHelper.parseStrictJsonObject(
        arguments, "function_call arguments must be a JSON object");
    facts.toolCalls.add(new ProviderToolCall(callId, item.get("name").stringValue(), arguments));
  }

  private static DurableFacts collectDurableFacts(List<ProviderContentBlock> blocks) {
    DurableFacts facts = new DurableFacts();
    for (ProviderContentBlock block : blocks) {
      if (block instanceof ProviderTextBlock textBlock) {
        facts.text.append(textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        facts.thinking.append(thinkingBlock.thinking());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        ProviderJsonHelper.parseStrictJsonObject(
            call.argumentsJson(), "durable tool call arguments must be a JSON object");
        facts.toolCalls.add(call);
      }
    }
    return facts;
  }

  private static void requireTextMatches(ReplayFacts replay, DurableFacts durable) {
    if (!replay.text.toString().equals(durable.text.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay text content mismatch with durable message content");
    }
  }

  private static void requireThinkingMatches(ReplayFacts replay, DurableFacts durable) {
    if (replay.isEmptyPlaceholderOnly()) {
      return;
    }
    if (!durable.thinking.isEmpty()
        && !replay.thinking.toString().equals(durable.thinking.toString())) {
      if (!(replay.thinking.isEmpty() && replay.hasOpaqueReasoning)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay thinking content mismatch with durable message thinking");
      }
      // 携带 opaque 密文且没有可比可读文本的 reasoning 是原生推理的权威事实：durable 可读思考无法在 payload 内表达，
      // 但也不能据此拒绝。原位回放保留密文原生事实；编码器会把缺失的可读思考作为普通 assistant 文本附带，不猜测、不改写密文。
      return;
    }
    if (durable.thinking.isEmpty() && !replay.thinking.isEmpty()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay thinking content mismatch with durable message thinking");
    }
  }

  private static void requireToolCallsMatch(
      List<ProviderToolCall> replayToolCalls, List<ProviderToolCall> durableToolCalls) {
    if (replayToolCalls.size() != durableToolCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay tool calls count mismatch with durable tool calls");
    }
    for (int i = 0; i < replayToolCalls.size(); i++) {
      ProviderToolCall replay = replayToolCalls.get(i);
      ProviderToolCall durable = durableToolCalls.get(i);
      if (!replay.id().equals(durable.id())
          || !replay.name().equals(durable.name())
          || !replay.argumentsJson().equals(durable.argumentsJson())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay tool call mismatch with durable tool call at index " + i);
      }
    }
  }

  /** 节点是否携带给定白名单之外的成员：reasoning 额外成员都是 durable 语义无法表达的原生事实。 */
  private static boolean hasFieldOutside(JsonNode node, Set<String> allowedFields) {
    for (String field : node.propertyNames()) {
      if (!allowedFields.contains(field)) {
        return true;
      }
    }
    return false;
  }

  private static void requireOptionalNonBlankString(
      JsonNode item, String field, String errorMessage) {
    if (!item.has(field)) {
      return;
    }
    JsonNode value = item.get(field);
    if (!value.isString() || value.stringValue().isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
  }

  /** reply payload 中参与 durable 一致性比对的事实。 */
  private static final class ReplayFacts {
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<ProviderToolCall> toolCalls = new ArrayList<>();
    private int reasoningItemCount = 0;
    private int emptyReasoningPlaceholderCount = 0;
    private boolean hasOpaqueReasoning = false;

    /** 只由空 reasoning 占位符构成：至少一个 reasoning，且每个都无密文、无任何非空摘要文本、无额外成员。 */
    private boolean isEmptyPlaceholderOnly() {
      return reasoningItemCount > 0 && emptyReasoningPlaceholderCount == reasoningItemCount;
    }
  }

  /** durable assistant block 中参与一致性比对的事实。 */
  private static final class DurableFacts {
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<ProviderToolCall> toolCalls = new ArrayList<>();
  }
}
