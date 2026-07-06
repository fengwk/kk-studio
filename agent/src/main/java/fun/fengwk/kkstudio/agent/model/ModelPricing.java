package fun.fengwk.kkstudio.agent.model;

import lombok.Builder;
import lombok.Data;

/**
 * ModelPricing 表示模型价格信息。
 *
 * @author fengwk
 */
@Builder
@Data
public class ModelPricing {

  /** 输入价格。 */
  private final Double input;

  /** 输出价格。 */
  private final Double output;

  /** 缓存读取价格。 */
  private final Double cacheRead;

  /** 缓存写入价格。 */
  private final Double cacheWrite;
}
