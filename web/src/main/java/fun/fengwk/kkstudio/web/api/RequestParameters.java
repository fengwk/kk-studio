package fun.fengwk.kkstudio.web.api;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

/** HTTP 边界的共享参数适配：只翻译共同解析器的拒绝，不复制值算法。 */
public final class RequestParameters {

  private RequestParameters() {}

  /** 解析路径或查询中的 Environment 身份；非法输入固定为 environmentId 的校验错误。 */
  public static EnvironmentId environmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environmentId", "environmentId must be a canonical UUID");
    }
  }
}
