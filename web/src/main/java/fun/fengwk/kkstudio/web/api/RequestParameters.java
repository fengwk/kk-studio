package fun.fengwk.kkstudio.web.api;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.UUID;

/** HTTP 边界的共享参数适配：只翻译共同解析器的拒绝，不复制值算法。 */
public final class RequestParameters {

  private RequestParameters() {}

  /** 解析路径或查询中的 Environment 身份；非法输入固定为 environmentId 的校验错误。 */
  public static EnvironmentId environmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (RuntimeException error) {
      throw new AiValidationException("environmentId", "environmentId must be a canonical UUID");
    }
  }

  /** 解析内部 IDE 数据面的 viewId；缺失与非 canonical 保持各自的固定拒绝文案。 */
  public static UUID ideViewId(String value) {
    if (value == null) {
      throw new AiValidationException("environment", "internal IDE viewId is missing");
    }
    try {
      return Uuids.parseCanonical(value, "viewId");
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environment", "internal IDE viewId is invalid");
    }
  }
}
