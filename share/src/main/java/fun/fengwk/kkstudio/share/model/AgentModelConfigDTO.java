package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/**
 * Structured Agent model configuration. This is the single source of truth exposed by the public
 * AgentModel API and persisted as a single {@code config_json} column by the repository.
 *
 * <p>Schema (no {@code modalities}, no separate {@code capabilities}, no nested modalities):
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

  private AgentModelLimitDTO limit;
  private AgentModelAbilitiesDTO abilities;
  private AgentModelPricingDTO pricing;
  private String defaultVariant;
  private List<AgentModelVariantDTO> variants;
}
