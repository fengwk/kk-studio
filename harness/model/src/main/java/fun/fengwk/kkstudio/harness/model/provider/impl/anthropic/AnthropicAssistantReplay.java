package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages 的 assistant 消息编码：native replay 优先，亲和性不匹配时按 durable 语义回退投影。
 *
 * <p>replay 与 system/时间前缀无关，只与 replay state 的 affinity（Provider type/name/wire model）绑定。affinity
 * 不匹配时按 durable 语义回退投影，绝不因为存在 replay 而拒绝；affinity 匹配时先校验真实 payload 形状与 durable 一致性，再原位回放全部原生事实（含
 * {@code thinking} 的 signature、{@code redacted_thinking}、未知 block 与官方附加字段）。 payload 以 {@code
 * compaction} block 开头时代表历史已被摘要，调用方据此省略此前的 wire messages。
 */
final class AnthropicAssistantReplay {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  /** 语义比对专用读取器：数值按 scale 无关归一，只用于 replay 与 durable 的一致性比对，不参与 wire 输出。 */
  private static final ObjectReader COMPARISON_READER =
      OBJECT_MAPPER.reader().with(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private AnthropicAssistantReplay() {}

  static AnthropicEncodedAssistant encodeMessage(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "assistant");

    ReplayDecision replay = evaluateBlockReplay(message, descriptor, requestedModel);
    if (replay.replayable()) {
      ArrayNode replayedBlocks = NODES.arrayNode();
      for (JsonNode blockNode : replay.content()) {
        replayedBlocks.add(blockNode.deepCopy());
      }
      msgNode.set("content", replayedBlocks);
      return new AnthropicEncodedAssistant(msgNode, replay.startsHistory());
    }

    // Semantic fallback
    ArrayNode contents = msgNode.putArray("content");
    for (ProviderContentBlock block : message.contents()) {
      ObjectNode fallback = encodeAssistantFallbackBlock(block);
      if (fallback != null) {
        contents.add(fallback);
      }
    }
    return new AnthropicEncodedAssistant(msgNode, false);
  }

  private static ReplayDecision evaluateBlockReplay(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    if (message.contents().isEmpty()) {
      return ReplayDecision.FALLBACK;
    }
    ArrayNode content = NODES.arrayNode();
    for (ProviderContentBlock block : message.contents()) {
      ProviderBlockData data = blockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.ANTHROPIC_MESSAGES
          || !data.affinity().equals(descriptor.affinity(requestedModel))) {
        return ReplayDecision.FALLBACK;
      }
      ObjectNode nativeBlock = NODES.objectNode();
      if (block instanceof ProviderTextBlock textBlock) {
        nativeBlock.put("type", "text");
        nativeBlock.put("text", textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        if (data.signature() == null || data.signature().isBlank()) {
          return ReplayDecision.FALLBACK;
        }
        nativeBlock.put("type", "thinking");
        nativeBlock.put("thinking", thinkingBlock.thinking());
        nativeBlock.put("signature", data.signature());
      } else if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
        nativeBlock.put("type", "redacted_thinking");
        nativeBlock.put("data", redactedBlock.data());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        nativeBlock.put("type", "tool_use");
        nativeBlock.put("id", call.id());
        nativeBlock.put("name", call.name());
        nativeBlock.set(
            "input",
            ProviderJsonHelper.parseStrictJsonObject(
                call.argumentsJson(), "tool_use input must be a JSON object"));
      } else if (block instanceof ProviderOpaqueBlock opaqueBlock) {
        nativeBlock.put("type", opaqueBlock.blockType());
      } else {
        return ReplayDecision.FALLBACK;
      }
      if (!mergeNativeFields(nativeBlock, data)) {
        return ReplayDecision.FALLBACK;
      }
      content.add(nativeBlock);
    }
    boolean startsHistory = validatePayloadAgainstDurable(content, message.contents());
    return new ReplayDecision(true, content, startsHistory);
  }

  private static ProviderBlockData blockData(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      return textBlock.providerBlockData();
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      return thinkingBlock.providerBlockData();
    }
    if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
      return redactedBlock.providerBlockData();
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      return toolCallBlock.providerBlockData();
    }
    if (block instanceof ProviderOpaqueBlock opaqueBlock) {
      return opaqueBlock.providerBlockData();
    }
    return null;
  }

  private static boolean mergeNativeFields(ObjectNode target, ProviderBlockData data) {
    JsonNode fields = data.nativeFields();
    for (var entry : fields.properties()) {
      if (target.has(entry.getKey())) {
        return false;
      }
      target.set(entry.getKey(), entry.getValue().deepCopy());
    }
    return true;
  }

  /**
   * 校验 native replay payload 的真实 block 形状与 durable 语义一致性，返回 payload 是否代表已摘要历史。
   *
   * <p>未知但合法的 provider block 与官方附加字段 opaque 透传；已知 block 只校验 durable 语义所必需的字段。
   */
  private static boolean validatePayloadAgainstDurable(
      ArrayNode contentArray, List<ProviderContentBlock> durableContents) {
    ReplayPayload payload = parseReplayPayload(contentArray);
    DurableSummary durable = summarizeDurable(durableContents);
    requireMatchingSemantics(payload, durable);
    return payload.compactionFirst();
  }

  /** payload 只接受已知 block 的形状；未知 type opaque 透传，但 compaction 最多一个且必须在首位。 */
  private static ReplayPayload parseReplayPayload(ArrayNode contentArray) {
    StringBuilder text = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    List<ProviderToolCall> calls = new ArrayList<>();
    List<String> redacted = new ArrayList<>();

    int compactionIndex = -1;
    int blockIndex = 0;
    for (JsonNode item : contentArray) {
      String type = replayBlockType(item);
      switch (type) {
        case "text" -> {
          if (!item.has("text") || !item.get("text").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
          }
          validateCitations(item.get("citations"));
          text.append(item.get("text").stringValue());
        }
        case "thinking" -> {
          if (!item.has("thinking")
              || !item.get("thinking").isString()
              || !item.has("signature")
              || !item.get("signature").isString()
              || item.get("signature").stringValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid thinking block in replay payload");
          }
          thinking.append(item.get("thinking").stringValue());
        }
        case "redacted_thinking" -> {
          if (!item.has("data")
              || !item.get("data").isString()
              || item.get("data").stringValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "invalid redacted_thinking block in replay payload");
          }
          redacted.add(item.get("data").stringValue());
        }
        case "tool_use" -> calls.add(replayToolCall(item));
        case "compaction" -> {
          if (compactionIndex >= 0) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "duplicate compaction block in replay payload");
          }
          // 摘要块必须整体位于消息首部，代表此前历史已被压缩
          compactionIndex = blockIndex;
        }
        default -> {
          // 未知但合法的 provider block：opaque 透传，不参与 durable 一致性比对，也绝不伪装成已知 type
        }
      }
      blockIndex++;
    }

    if (compactionIndex > 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "compaction block must be the first content block of the replay message");
    }
    return new ReplayPayload(text, thinking, calls, redacted, compactionIndex == 0);
  }

  /** replay block 必须是带非空 string type 的对象。 */
  private static String replayBlockType(JsonNode item) {
    if (!item.isObject() || !item.has("type") || !item.get("type").isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload block");
    }
    String type = item.get("type").stringValue();
    if (type.isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic replay payload block");
    }
    return type;
  }

  /** tool_use 的 id/name/input 必须齐备且形状正确。 */
  private static ProviderToolCall replayToolCall(JsonNode item) {
    if (!item.has("id")
        || !item.get("id").isString()
        || item.get("id").stringValue().isBlank()
        || !item.has("name")
        || !item.get("name").isString()
        || item.get("name").stringValue().isBlank()
        || !item.has("input")
        || !item.get("input").isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid tool_use block in replay payload");
    }
    return new ProviderToolCall(
        item.get("id").stringValue(), item.get("name").stringValue(), item.get("input").toString());
  }

  /** durable contents 投影成语义摘要；未知 block 类型属于请求事实损坏，必须拒绝。 */
  private static DurableSummary summarizeDurable(List<ProviderContentBlock> durableContents) {
    StringBuilder text = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    List<ProviderToolCall> calls = new ArrayList<>();
    List<String> redacted = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        text.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock pb) {
        thinking.append(pb.thinking());
      } else if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
        redacted.add(redactedBlock.data());
      } else if (block instanceof ProviderOpaqueBlock) {
        // opaque blocks are preserved for the same protocol but have no cross-model semantic
        // projection
      } else if (block instanceof ProviderToolCallBlock cb) {
        ProviderToolCall call = cb.toolCall();
        ProviderJsonHelper.parseStrictJsonObject(
            call.argumentsJson(), "durable tool call arguments must be a JSON object");
        calls.add(call);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unsupported durable block in assistant message");
      }
    }
    return new DurableSummary(text, thinking, calls, redacted);
  }

  /** 逐项比对语义；拒绝顺序与原实现一致：text、thinking、redacted、工具调用数量、逐个工具调用。 */
  private static void requireMatchingSemantics(ReplayPayload payload, DurableSummary durable) {
    if (!payload.text().toString().equals(durable.text().toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "replay payload text does not match durable content");
    }
    if (!payload.thinking().toString().equals(durable.thinking().toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload thinking does not match durable content");
    }
    if (!durable.redacted().isEmpty() && !payload.redacted().equals(durable.redacted())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload redacted thinking does not match durable content");
    }
    if (payload.calls().size() != durable.calls().size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload tool call count does not match durable content");
    }
    for (int i = 0; i < payload.calls().size(); i++) {
      requireMatchingToolCall(payload.calls().get(i), durable.calls().get(i));
    }
  }

  /** 单个工具调用的 id/name 与参数语义都必须一致。 */
  private static void requireMatchingToolCall(ProviderToolCall pCall, ProviderToolCall dCall) {
    if (!pCall.id().equals(dCall.id()) || !pCall.name().equals(dCall.name())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload tool call id/name does not match durable content");
    }
    // 合法性边界不变：两侧参数仍按 wire 同款严格对象校验。
    ProviderJsonHelper.parseStrictJsonObject(
        pCall.argumentsJson(), "replay tool call input must be a JSON object");
    ProviderJsonHelper.parseStrictJsonObject(
        dCall.argumentsJson(), "durable tool call arguments must be a JSON object");
    // 语义一致性按数值 scale 无关比对（1.0 与 1.00 同值）；比较专用 reader 只读取比较树，
    // wire 仍使用 replay 原生 input，输出形态不被改写。
    if (!COMPARISON_READER
        .readTree(pCall.argumentsJson())
        .equals(COMPARISON_READER.readTree(dCall.argumentsJson()))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay payload tool call arguments do not match durable content");
    }
  }

  /** citations 是官方可扩展结构：只校验容器与元素形状，不做字段白名单。 */
  private static void validateCitations(JsonNode citationsNode) {
    if (citationsNode == null || citationsNode.isNull()) {
      return;
    }
    if (!citationsNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
    }
    for (JsonNode citation : citationsNode) {
      if (!citation.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid text block in replay payload");
      }
    }
  }

  private static ObjectNode encodeAssistantFallbackBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      if (thinkingBlock.thinking().isEmpty()) {
        return null;
      }
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", thinkingBlock.thinking());
      return node;
    }
    if (block instanceof ProviderRedactedThinkingBlock || block instanceof ProviderOpaqueBlock) {
      return null;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      // jsonBlock 降级为普通 text 回放（如 tool_call_diagnostic）
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", jsonBlock.json());
      return node;
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      ProviderToolCall call = toolCallBlock.toolCall();
      ObjectNode node = NODES.objectNode();
      node.put("type", "tool_use");
      node.put("id", call.id());
      node.put("name", call.name());
      JsonNode inputNode =
          ProviderJsonHelper.parseStrictJsonObject(
              call.argumentsJson(), "tool_use input must be a JSON object");
      node.set("input", inputNode);
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported assistant block type");
  }

  /** replay 判定结果；{@code startsHistory} 为真当且仅当 payload 以 compaction block 开头。 */
  private record ReplayPayload(
      StringBuilder text,
      StringBuilder thinking,
      List<ProviderToolCall> calls,
      List<String> redacted,
      boolean compactionFirst) {}

  private record DurableSummary(
      StringBuilder text,
      StringBuilder thinking,
      List<ProviderToolCall> calls,
      List<String> redacted) {}

  private record ReplayDecision(boolean replayable, ArrayNode content, boolean startsHistory) {

    static final ReplayDecision FALLBACK = new ReplayDecision(false, null, false);
  }
}
