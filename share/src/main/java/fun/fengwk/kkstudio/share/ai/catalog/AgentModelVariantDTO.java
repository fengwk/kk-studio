package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/**
 * 单个 Agent 模型变体。所有可选字段都可以为 {@code null}（表示“不下发该字段，回退到 provider 默认值”）；{@code stopSequences}
 * 是一个可以为空的普通字符串列表。
 *
 * <p>数值字段使用 {@code Integer}，使 wire format 始终为普通 JSON 数字，不受 convention4j 将 {@code Long} 自动配置为字符串的影响。
 */
@Data
public class AgentModelVariantDTO {

  /** 必填变体 id：非空白、无环绕空白，且在 {@code config.variants} 内唯一。 */
  private String id;

  /** 可空 reasoning effort 值（如 low/medium/high，具体取值语义由 provider 定义）；null 表示不下发覆盖。 */
  private String reasoningEffort;

  /** 可空单次输出 Token 上限：为正整数且不得超过模型 limit.output。 */
  private Integer maxOutputTokens;

  /** 可空采样温度：有限且非负（取值范围由 provider 语义定义）。 */
  private Double temperature;

  /** 可空核采样 topP：有限且在 {@code (0, 1]} 区间。 */
  private Double topP;

  /** 可空 topK：正整数。 */
  private Integer topK;

  /** 可空频率惩罚：有限数（取值范围由 provider 语义定义）。 */
  private Double frequencyPenalty;

  /** 可空存在惩罚：有限数（取值范围由 provider 语义定义）。 */
  private Double presencePenalty;

  /** 可空的停止序列列表；元素必须为非空白字符串（可为空列表）。 */
  private List<String> stopSequences;
}
