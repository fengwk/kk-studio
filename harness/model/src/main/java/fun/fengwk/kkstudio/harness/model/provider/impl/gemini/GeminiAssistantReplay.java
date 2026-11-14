package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

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
import java.util.Objects;

/**
 * Gemini GenerateContent 的 assistant replay：从有序 blocks 的 Provider 元数据重建既则 parts。
 *
 * <p>只有同 protocol、provider/model affinity 匹配且 block 语义与 durable 内容一致时才原样回放；否则按 durable 语义编码。
 * 跨模型工具签名不会保留。known 字段类型损坏一律 {@code INVALID_REQUEST}，语义不一致则回退语义编码。
 */
final class GeminiAssistantReplay {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  /** 语义比对专用读取器：数值按 scale 无关归一，只用于 replay 与 durable 的一致性比对，不参与 wire 输出。 */
  private static final ObjectReader COMPARISON_READER =
      OBJECT_MAPPER.reader().with(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private GeminiAssistantReplay() {}

  static List<ObjectNode> buildParts(
      ProviderDescriptor descriptor, String requestedModel, List<ProviderContentBlock> blocks) {
    if (blocks.isEmpty()) {
      return null;
    }
    var requestedAffinity = descriptor.affinity(requestedModel);
    List<ObjectNode> parts = new ArrayList<>();
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "model");
    ArrayNode payloadParts = payload.putArray("parts");

    for (ProviderContentBlock block : blocks) {
      ProviderBlockData data = providerBlockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.GEMINI_CONTENT
          || !data.affinity().equals(requestedAffinity)) {
        return null;
      }

      ObjectNode part = NODES.objectNode();
      JsonNode functionCallExtras = null;
      ObjectNode nativeFields = (ObjectNode) data.nativeFields();
      if (block instanceof ProviderToolCallBlock) {
        functionCallExtras = nativeFields.remove("functionCall");
      }
      mergeNativeFields(part, nativeFields);
      if (block instanceof ProviderThinkingBlock thinkingBlock) {
        part.put("text", thinkingBlock.thinking());
        part.put("thought", data.thought() == null || data.thought().booleanValue());
      } else if (block instanceof ProviderTextBlock textBlock) {
        part.put("text", textBlock.text());
        if (data.thought() != null) {
          part.put("thought", data.thought().booleanValue());
        }
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        ObjectNode functionCall = part.putObject("functionCall");
        if (functionCallExtras != null) {
          mergeNativeFields(functionCall, functionCallExtras);
        }
        functionCall.put("name", call.name());
        if (call.id() != null && !call.id().isBlank()) {
          functionCall.put("id", call.id());
        }
        JsonNode args =
            ProviderJsonHelper.parseStrictJson(
                call.argumentsJson(), "tool call arguments must be a valid JSON object");
        if (!args.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "tool call arguments must be a valid JSON object");
        }
        functionCall.set("args", args);
      } else if (block instanceof ProviderOpaqueBlock opaqueBlock) {
        if (!"gemini_part".equals(opaqueBlock.blockType())) {
          return null;
        }
      } else {
        return null;
      }
      if (data.thought() != null && !(block instanceof ProviderTextBlock)) {
        part.put("thought", data.thought().booleanValue());
      }
      if (data.signature() != null) {
        part.put("thoughtSignature", data.signature());
      }
      parts.add(part);
      payloadParts.add(part.deepCopy());
    }

    if (!validateReplayPayload(payload, blocks)) {
      return null;
    }
    return parts;
  }

  private static ProviderBlockData providerBlockData(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      return textBlock.providerBlockData();
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      return thinkingBlock.providerBlockData();
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      return toolCallBlock.providerBlockData();
    }
    if (block instanceof ProviderOpaqueBlock opaqueBlock) {
      return opaqueBlock.providerBlockData();
    }
    if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
      return redactedBlock.providerBlockData();
    }
    return null;
  }

  private static void mergeNativeFields(ObjectNode target, JsonNode fields) {
    if (fields == null || !fields.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini assistant block native fields");
    }
    for (var field : fields.properties()) {
      target.set(field.getKey(), field.getValue().deepCopy());
    }
  }

  static void encodeBlock(ArrayNode parts, ProviderContentBlock block) {
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      if (thinkingBlock.thinking() == null || thinkingBlock.thinking().isEmpty()) {
        return;
      }
      ObjectNode part = parts.addObject();
      part.put("text", thinkingBlock.thinking());
      return;
    }
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode part = parts.addObject();
      part.put("text", textBlock.text());
      return;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      // assistant JSON 诊断（如 tool_call_diagnostic）降级为普通文本 part。
      ObjectNode part = parts.addObject();
      part.put("text", jsonBlock.json());
      return;
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      ProviderToolCall call = toolCallBlock.toolCall();
      ObjectNode part = parts.addObject();
      ObjectNode functionCall = part.putObject("functionCall");
      functionCall.put("name", call.name());
      if (call.id() != null && !call.id().isBlank()) {
        functionCall.put("id", call.id());
      }
      JsonNode argsNode =
          ProviderJsonHelper.parseStrictJson(
              call.argumentsJson(), "tool call arguments must be a valid JSON object");
      if (!argsNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "tool call arguments must be a valid JSON object");
      }
      functionCall.set("args", argsNode);
      return;
    }
    if (block instanceof ProviderOpaqueBlock || block instanceof ProviderRedactedThinkingBlock) {
      return;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "unsupported ASSISTANT content block");
  }

  /** thoughtSignature 属于已知字段：出现时必须是非空白字符串；作为 opaque 原生事实只在原位回放时使用。 */
  private static void validateReplayThoughtSignature(JsonNode part) {
    if (part.has("thoughtSignature")
        && (!part.get("thoughtSignature").isString()
            || part.get("thoughtSignature").asString("").isBlank())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }
  }

  /**
   * 完成根结构校验、已知字段类型校验与 durable 一致性比对：结构/类型损坏一律 INVALID_REQUEST；返回 payload 的语义投影是否与 durable
   * 内容完全一致（不一致表示历史被改写，调用方应退回语义编码而非回放陈旧 payload）。
   */
  static boolean validateReplayPayload(
      JsonNode payload, List<ProviderContentBlock> durableContents) {
    ReplayPayloadSummary payloadSummary = summarizeReplayPayload(requirePayloadParts(payload));
    DurableSummary durableSummary = summarizeDurable(durableContents);
    return semanticsMatch(payloadSummary, durableSummary);
  }

  /** payload 只能是 {@code {role:"model", parts:[...]}}：顶级字段白名单与 role/parts 类型损坏一律拒绝。 */
  private static ArrayNode requirePayloadParts(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw invalidReplayPayload();
    }

    for (String field : payload.propertyNames()) {
      if (!"role".equals(field) && !"parts".equals(field)) {
        throw invalidReplayPayload();
      }
    }

    if (!payload.has("role")
        || !payload.get("role").isString()
        || !"model".equals(payload.get("role").asString(""))) {
      throw invalidReplayPayload();
    }

    if (!payload.has("parts") || !payload.get("parts").isArray()) {
      throw invalidReplayPayload();
    }
    return (ArrayNode) payload.get("parts");
  }

  /** 顺序扫描 payload parts，投影出 text、thinking 与 function 调用；未知 Part 作为 opaque 原生事实跳过。 */
  private static ReplayPayloadSummary summarizeReplayPayload(ArrayNode partsArray) {
    StringBuilder text = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    List<ReplayPayloadCall> calls = new ArrayList<>();

    for (JsonNode item : partsArray) {
      requireKnownPartShape(item);
      if (item.has("text")) {
        appendTextPart(item, text, thinking);
      } else if (item.has("functionCall")) {
        calls.add(readFunctionCallPart(item));
      }
      // 其余官方 Part 与未知 Part 都作为 opaque 原生事实：只在 affinity 匹配时原位回放。
    }
    return new ReplayPayloadSummary(text.toString(), thinking.toString(), calls);
  }

  /** 已知语义（text/functionCall）互斥；thoughtSignature 虽只用于 opaque 原生事实，仍须先于跳过校验。 */
  private static void requireKnownPartShape(JsonNode item) {
    if (!item.isObject()) {
      throw invalidReplayPayload();
    }
    if (item.has("text") && item.has("functionCall")) {
      throw invalidReplayPayload();
    }
    validateReplayThoughtSignature(item);
  }

  /** text part 的 text 必为字符串、thought 必为布尔；thinking 与普通文本分别累计。 */
  private static void appendTextPart(JsonNode item, StringBuilder text, StringBuilder thinking) {
    if (!item.get("text").isString()) {
      throw invalidReplayPayload();
    }
    if (item.has("thought") && !item.get("thought").isBoolean()) {
      throw invalidReplayPayload();
    }

    String value = item.get("text").asString("");
    if (item.has("thought") && item.get("thought").asBoolean(false)) {
      thinking.append(value);
    } else {
      text.append(value);
    }
  }

  /** functionCall 的 name 必为字符串、args 必为对象，id 若出现必为字符串。 */
  private static ReplayPayloadCall readFunctionCallPart(JsonNode item) {
    JsonNode functionCall = item.get("functionCall");
    if (!functionCall.isObject()) {
      throw invalidReplayPayload();
    }
    if (!functionCall.has("name") || !functionCall.get("name").isString()) {
      throw invalidReplayPayload();
    }
    if (!functionCall.has("args") || !functionCall.get("args").isObject()) {
      throw invalidReplayPayload();
    }
    if (functionCall.has("id") && !functionCall.get("id").isString()) {
      throw invalidReplayPayload();
    }

    String id = functionCall.has("id") ? functionCall.get("id").asString("") : null;
    return new ReplayPayloadCall(
        id, functionCall.get("name").asString(""), functionCall.get("args").toString());
  }

  /** durable contents 投影成语义摘要；无法投影的块类型属于请求事实损坏，必须拒绝。 */
  private static DurableSummary summarizeDurable(List<ProviderContentBlock> durableContents) {
    StringBuilder text = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    List<ProviderToolCall> calls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock textBlock) {
        text.append(textBlock.text() != null ? textBlock.text() : "");
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        thinking.append(thinkingBlock.thinking() != null ? thinkingBlock.thinking() : "");
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        requireDurableToolArguments(call);
        calls.add(call);
      } else if (block instanceof ProviderOpaqueBlock
          || block instanceof ProviderRedactedThinkingBlock) {
        // Native opaque and redacted content does not contribute to durable semantic comparison.
      } else {
        throw invalidReplayPayload();
      }
    }
    return new DurableSummary(text.toString(), thinking.toString(), calls);
  }

  /** durable 工具参数必须是可严格解析的 JSON object。 */
  private static void requireDurableToolArguments(ProviderToolCall call) {
    JsonNode arguments =
        ProviderJsonHelper.parseStrictJson(call.argumentsJson(), "invalid Gemini replay payload");
    if (!arguments.isObject()) {
      throw invalidReplayPayload();
    }
  }

  /** 逐项比对语义；拒绝顺序不变：text、thinking、调用数量、逐个调用。不一致返回 false 以回退语义编码。 */
  private static boolean semanticsMatch(ReplayPayloadSummary payload, DurableSummary durable) {
    if (!payload.text().equals(durable.text())) {
      return false;
    }
    if (!payload.thinking().equals(durable.thinking())) {
      return false;
    }
    if (payload.calls().size() != durable.calls().size()) {
      return false;
    }
    for (int i = 0; i < payload.calls().size(); i++) {
      if (!toolCallMatches(payload.calls().get(i), durable.calls().get(i))) {
        return false;
      }
    }
    return true;
  }

  /** 单个工具调用的 name、空白归一后的 id 与参数语义都必须一致。 */
  private static boolean toolCallMatches(
      ReplayPayloadCall payloadCall, ProviderToolCall durableCall) {
    if (!Objects.equals(payloadCall.name(), durableCall.name())) {
      return false;
    }
    if (!Objects.equals(blankAsNull(payloadCall.id()), blankAsNull(durableCall.id()))) {
      return false;
    }
    return argumentsMatch(payloadCall.argsJson(), durableCall.argumentsJson());
  }

  /** functionCall id 的空白与缺失等价。 */
  private static String blankAsNull(String id) {
    return id != null && !id.isBlank() ? id : null;
  }

  /** 参数按数值 scale 无关比对（1.0 与 1.00 同值）；比较专用 reader 只读取比较树，wire 仍用 replay 原生 args。 */
  private static boolean argumentsMatch(String payloadArgs, String durableArgs) {
    JsonNode payloadArguments;
    JsonNode durableArguments;
    try {
      payloadArguments = COMPARISON_READER.readTree(payloadArgs);
      durableArguments = COMPARISON_READER.readTree(durableArgs);
    } catch (Exception e) {
      // durable 参数本身无法解析属于请求事实损坏，必须明确拒绝而非静默 fallback。
      throw invalidReplayPayload();
    }
    return Objects.equals(payloadArguments, durableArguments);
  }

  private static ProviderException invalidReplayPayload() {
    return new ProviderException(
        ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
  }

  /** payload 的语义投影：text/thinking 拼接结果与 function 调用序列。 */
  private record ReplayPayloadSummary(
      String text, String thinking, List<ReplayPayloadCall> calls) {}

  /** payload 中参与 durable 比对的 function 调用；{@code argsJson} 保留 wire 原生参数树。 */
  private record ReplayPayloadCall(String id, String name, String argsJson) {}

  /** durable contents 的语义投影。 */
  private record DurableSummary(String text, String thinking, List<ProviderToolCall> calls) {}
}
