package fun.fengwk.kkstudio.harness.model.cache;

/**
 * A cache mode applicable to one provider endpoint and model. Unknown TTL or multiplier is null.
 */
public record PromptCacheOption(
    PromptCacheRetention retention, Long ttlSeconds, Double writeMultiplier) {}
