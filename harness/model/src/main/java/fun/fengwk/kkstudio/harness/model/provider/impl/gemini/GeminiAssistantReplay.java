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

    if (!validateGeminiReplayPayload(payload, blocks)) {
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
   * 完成结构校验、已知字段类型校验与 durable 一致性比对：结构/类型损坏一律 INVALID_REQUEST；返回 payload 的语义投影是否与 durable
   * 内容完全一致（不一致表示历史被改写，调用方应退回语义编码而非回放陈旧 payload）。
   */
  private static boolean validateGeminiReplayPayload(
      JsonNode payload, List<ProviderContentBlock> durableContents) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    // 严格检查顶级字段白名单：只能有 role 和 parts，禁止无关字段混入
    for (String f : payload.propertyNames()) {
      if (!"role".equals(f) && !"parts".equals(f)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }

    if (!payload.has("role")
        || !payload.get("role").isString()
        || !"model".equals(payload.get("role").asString(""))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    if (!payload.has("parts") || !payload.get("parts").isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
    }

    ArrayNode partsArray = (ArrayNode) payload.get("parts");

    StringBuilder payloadText = new StringBuilder();
    StringBuilder payloadThinking = new StringBuilder();
    record ReplayPayloadCall(String id, String name, String argsJson) {}
    List<ReplayPayloadCall> payloadCalls = new ArrayList<>();

    for (JsonNode item : partsArray) {
      if (!item.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }

      // 已知语义（text/thinking/functionCall）严格核对；未知 Part 与额外成员作为 opaque 原生事实原样保留。
      boolean hasText = item.has("text");
      boolean hasFunctionCall = item.has("functionCall");

      if (hasText && hasFunctionCall) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }

      validateReplayThoughtSignature(item);

      if (!hasText && !hasFunctionCall) {
        // inlineData/fileData/executableCode/codeExecutionResult/toolCall/toolResponse 等官方 Part 与未知
        // Part 都作为 opaque 原生事实：只在 affinity 匹配时原位回放。
        continue;
      }

      if (hasText) {
        if (!item.get("text").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (item.has("thought") && !item.get("thought").isBoolean()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }

        String txt = item.get("text").asString("");
        if (item.has("thought") && item.get("thought").asBoolean(false)) {
          payloadThinking.append(txt);
        } else {
          payloadText.append(txt);
        }
      } else { // hasFunctionCall
        JsonNode fn = item.get("functionCall");
        if (!fn.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }

        if (!fn.has("name") || !fn.get("name").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (!fn.has("args") || !fn.get("args").isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        if (fn.has("id") && !fn.get("id").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }

        String name = fn.get("name").asString("");
        String id = fn.has("id") ? fn.get("id").asString("") : null;
        String argsJson = fn.get("args").toString();
        payloadCalls.add(new ReplayPayloadCall(id, name, argsJson));
      }
    }

    // 提取 durable 内容
    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text() != null ? tb.text() : "");
      } else if (block instanceof ProviderThinkingBlock tb) {
        durableThinking.append(tb.thinking() != null ? tb.thinking() : "");
      } else if (block instanceof ProviderToolCallBlock tcb) {
        ProviderToolCall call = tcb.toolCall();
        JsonNode dNode =
            ProviderJsonHelper.parseStrictJson(
                call.argumentsJson(), "invalid Gemini replay payload");
        if (!dNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
        }
        durableCalls.add(call);
      } else if (block instanceof ProviderOpaqueBlock
          || block instanceof ProviderRedactedThinkingBlock) {
        // Native opaque and redacted content does not contribute to durable semantic comparison.
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
    }

    // durable 语义一致性：不一致返回 false（退回语义编码），结构/类型损坏已在上面抛出。
    if (!payloadText.toString().equals(durableText.toString())) {
      return false;
    }
    if (!payloadThinking.toString().equals(durableThinking.toString())) {
      return false;
    }
    if (payloadCalls.size() != durableCalls.size()) {
      return false;
    }
    for (int i = 0; i < payloadCalls.size(); i++) {
      ReplayPayloadCall pCall = payloadCalls.get(i);
      ProviderToolCall dCall = durableCalls.get(i);
      if (!Objects.equals(pCall.name(), dCall.name())) {
        return false;
      }
      String pId = (pCall.id() != null && !pCall.id().isBlank()) ? pCall.id() : null;
      String dId = (dCall.id() != null && !dCall.id().isBlank()) ? dCall.id() : null;
      if (!Objects.equals(pId, dId)) {
        return false;
      }
      JsonNode pArgs;
      JsonNode dArgs;
      try {
        // 数值按 scale 无关比对（1.0 与 1.00 同值）；比较专用 reader 只读取比较树，wire 仍用 replay 原生 args。
        pArgs = COMPARISON_READER.readTree(pCall.argsJson());
        dArgs = COMPARISON_READER.readTree(dCall.argumentsJson());
      } catch (Exception e) {
        // durable 参数本身无法解析属于请求事实损坏，必须明确拒绝而非静默 fallback。
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid Gemini replay payload");
      }
      if (!Objects.equals(pArgs, dArgs)) {
        return false;
      }
    }
    return true;
  }
}
