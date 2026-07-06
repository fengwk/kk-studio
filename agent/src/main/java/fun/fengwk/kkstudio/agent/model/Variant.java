package fun.fengwk.kkstudio.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Variant 表示模型的一种请求参数变体。
 *
 * <p>语义说明： - 第一版 variant 直接对应事件中的 variant 名称。 - 通用生成参数平铺在自身字段中。 - provider 专有参数通过键值对承载。
 *
 * @author fengwk
 */
@Builder
@Data
public class Variant {

  /** variant 名称。 */
  private final String name;

  /** 最大输出 token 数。 */
  private final Integer maxOutputTokens;

  /** 采样温度。 */
  private final Double temperature;

  /** nucleus sampling 参数。 */
  private final Double topP;

  /** top-k 采样参数。 */
  private final Integer topK;

  /** 频率惩罚参数。 */
  private final Double frequencyPenalty;

  /** 存在惩罚参数。 */
  private final Double presencePenalty;

  /** 随机种子。 */
  private final Integer seed;

  /** 停止序列列表。 */
  private final List<String> stopSequences;

  /** provider 专有参数键值表。 */
  private final Map<String, Object> providerOptions;
}
