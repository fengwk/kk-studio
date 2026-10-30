package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * request-body 定价条件：对最终编码 Provider JSON 请求体执行 RFC 6901 JSON Pointer 与 typed scalar 相等判定。
 *
 * <p>{@code path} 与 {@code equals} 必须同时存在；{@code equals} 可显式写 JSON null 表示只匹配存在的 null。数字在 wire 上保持
 * JSON number，不转成字符串。
 */
@Data
public class AgentModelPricingRequestBodyConditionDTO {

  /** 非空、以 {@code /} 开头且只含 ~0/~1 转义的 JSON Pointer。 */
  private @Nullable String path;

  /** JSON string、number、boolean 或 null；字段缺失与显式 null 语义不同。 */
  private @Nullable JsonNode equals;
}
