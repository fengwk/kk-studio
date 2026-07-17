package fun.fengwk.kkstudio.harness.runtime.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 严格解码 execution policy JSON 中由本切片关心的 control 字段。
 *
 * <p>输入必须为 JSON object；其它字段（如 maxTurns/maxDepth）一律忽略，只对 {@code steeringMode} 与 {@code followUpMode}
 * 做严格解析。两字段缺失或为 null 都视为默认 {@link ControlConsumptionMode#ONE_AT_A_TIME}；只接受字面量字符串 {@code
 * ONE_AT_A_TIME} 或 {@code ALL}，其余类型或值明确报错。
 */
public final class ControlPolicyCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ControlPolicyCodec() {}

  /** 解码 execution policy JSON 中的 control 字段为冻结 policy。 */
  public static ControlPolicy decode(String executionPolicyJson) {
    if (executionPolicyJson == null) {
      throw new IllegalArgumentException("execution policy must not be null");
    }
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(executionPolicyJson);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("execution policy must be valid JSON", exception);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("execution policy must be a JSON object");
    }
    return new ControlPolicy(decodeMode(root, "steeringMode"), decodeMode(root, "followUpMode"));
  }

  private static ControlConsumptionMode decodeMode(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return ControlConsumptionMode.ONE_AT_A_TIME;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(
          "execution policy." + field + " must be a string when present");
    }
    String text = value.textValue();
    try {
      return ControlConsumptionMode.valueOf(text);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "execution policy." + field + " must be one of ONE_AT_A_TIME/ALL but was: " + text,
          exception);
    }
  }
}
