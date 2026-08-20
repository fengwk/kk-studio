package fun.fengwk.kkstudio.harness.runtime.processor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 校验最终 Provider response 的 canonical 不变量与压缩调用约束。
 *
 * <p>Canonical 不变量：stop reason 非空；tool call ID 唯一；call ID/name 非空；arguments 是合法 JSON object； {@code
 * FILTERED} 的 toolCalls 必须为空。usage/cost 的合法性（非负、total 精确等于分项和）由 {@code ModelUsage} / {@code
 * ModelCost} 构造器保证，此处不再重复校验。Tool 是否在冻结 binding 中可见、参数是否符合 Tool schema 属于 {@link
 * ModelResponsePlanner} 的决策，不在此处校验；stop reason 与 tool call 存在性正交，无等价约束。
 *
 * <p>Compaction 的 stop reason、tool intent、摘要内容与 no-gain 是 reducer 语义，不在 Provider transport
 * 边界改写为重试；这里只拒绝所有调用都不可能接受的 malformed response。
 */
final class ModelResponseValidator {

  private static final ObjectMapper OBJECT_MAPPER = strictObjectMapper();

  private ModelResponseValidator() {}

  private static ObjectMapper strictObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    return mapper;
  }

  static ProviderResponse validate(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    if (response.stopReason() == null) {
      throw new IllegalArgumentException("provider response requires a stop reason");
    }
    Set<String> toolCallIds = new HashSet<>();
    for (ProviderToolCall call : response.toolCalls()) {
      if (call.id() == null || call.id().isBlank()) {
        throw new IllegalArgumentException("tool call ids must not be blank");
      }
      if (call.name() == null || call.name().isBlank()) {
        throw new IllegalArgumentException("tool call names must not be blank");
      }
      if (!toolCallIds.add(call.id())) {
        throw new IllegalArgumentException("tool call ids must be unique: " + call.id());
      }
      requireJsonObject(call.argumentsJson(), "tool call argumentsJson");
    }
    if (response.stopReason() == GenerationStopReason.FILTERED && !response.toolCalls().isEmpty()) {
      throw new IllegalArgumentException("FILTERED responses must not contain tool calls");
    }
    return response;
  }

  private static void requireJsonObject(String value, String name) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(value);
      if (!node.isObject()) {
        throw new IllegalArgumentException(name + " must contain a JSON object");
      }
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(name + " must contain JSON", exception);
    }
  }
}
