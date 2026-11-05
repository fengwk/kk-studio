package fun.fengwk.kkstudio.platform.http;

import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.error.DomainException;

/** HTTP 边界的共享参数适配：只翻译共同解析器的拒绝，不复制值算法。 */
public final class RequestParameters {

  private RequestParameters() {}

  /** 解析路径或查询中的 Environment 身份；非法输入固定为 environmentId 的校验错误。 */
  public static EnvironmentId environmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("environmentId", "environmentId must be a canonical UUID");
    }
  }

  /** 解析分页上限：缺省返回 defaultLimit；显式给出时必须是 [1, maxLimit] 内的 canonical 十进制整数。 */
  public static int limit(String limit, int defaultLimit, int maxLimit) {
    if (limit == null) {
      return defaultLimit;
    }
    long parsed = DecimalLongs.parsePositive(limit, "limit");
    if (parsed > maxLimit) {
      throw new IllegalArgumentException("limit must be within [1, " + maxLimit + "]");
    }
    return (int) parsed;
  }
}
