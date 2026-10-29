package fun.fengwk.kkstudio.harness.runtime.processor;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;

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
 * <p>这里只拒绝所有调用都不可能接受的 malformed response（transport 边界）。压缩摘要的完整性（无 tool intent 的 LENGTH 截断 final、空
 * final、FILTERED）由 {@link ModelExecution} 在冻结的压缩子身份事实上判定并走统一调用重试；摘要收益与父容量由 {@code
 * CompactionResultEvaluator} 在父侧校验。三者职责互不重叠。
 */
final class ModelResponseValidator {

  private static final ObjectMapper OBJECT_MAPPER = strictObjectMapper();

  private ModelResponseValidator() {}

  private static ObjectMapper strictObjectMapper() {
    return JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
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
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(name + " must contain JSON", exception);
    }
  }
}
