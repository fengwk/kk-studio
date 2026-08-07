package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/**
 * 结构化 Agent 模型配置。这是 public AgentModel API 对外暴露并持久化到 {@code agent_model.config} JSONB 列的唯一事实源。
 *
 * <p>Schema 如下：
 *
 * <pre>
 * limit:        { context: positive int, output: positive int &lt;= context }
 * abilities:    { tools: boolean, reasoning: boolean, inputModalities: non-empty list }
 * pricing:      { currency, pricingTier, serviceTier, serviceTierMultiplier, version,
 *                inputPerMillionTokens, outputPerMillionTokens, cacheReadPerMillionTokens,
 *                cacheWritePerMillionTokens, cacheWriteLongPerMillionTokens,
 *                reasoningPerMillionTokens }
 * defaultVariant: existing variant id
 * variants:     non-empty list with at least {@code id} (optional reasoningEffort,
 *                maxOutputTokens, temperature, topP, topK, frequencyPenalty,
 *                presencePenalty, stopSequences)
 * </pre>
 */
@Data
public class AgentModelConfigDTO {

  /** 必填 Token 限制：context 与 output 均为正整数且 output ≤ context。 */
  private AgentModelLimitDTO limit;

  /** 必填能力声明：tools/reasoning 必填，inputModalities 非空且去重。 */
  private AgentModelAbilitiesDTO abilities;

  /** 必填定价快照，用于每次调用的确定性成本计算。 */
  private AgentModelPricingDTO pricing;

  /** 必填默认变体 id：非空白、无环绕空白，且必须匹配 {@code variants[].id}。 */
  private String defaultVariant;

  /** 必填非空变体列表；元素 id 必须非空白且唯一。 */
  private List<AgentModelVariantDTO> variants;
}
