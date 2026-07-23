/**
 * Strict, deterministic JSON codec for {@code harness/model} Provider contracts.
 *
 * <p>{@link fun.fengwk.kkstudio.harness.model.provider.codec.ProviderRequestJsonCodec} and {@link
 * fun.fengwk.kkstudio.harness.model.provider.codec.ProviderResponseJsonCodec} are the single
 * persistence and IPC boundary for {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderRequest} and {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderResponse}. They:
 *
 * <ul>
 *   <li>build and read {@link com.fasterxml.jackson.databind.JsonNode} trees explicitly, forbidding
 *       Jackson default typing, polymorphic annotations, reflective POJO binding, and lenient
 *       unknown-field behavior;
 *   <li>round-trip the entire value-object graph including every {@link
 *       fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock} implementation, {@link
 *       fun.fengwk.kkstudio.harness.model.ModelDescriptor}, {@link
 *       fun.fengwk.kkstudio.harness.model.ModelVariant}, {@link
 *       fun.fengwk.kkstudio.harness.model.ModelPricing}, prompt-cache capability/policy/control,
 *       messages, tool definitions/calls, usage and cost;
 *   <li>require exact field sets at every nesting level; missing, unknown, wrong-type or unknown
 *       discriminator values raise {@link IllegalArgumentException};
 *   <li>preserve raw JSON strings ({@code json}, {@code argumentsJson}, {@code detailsJson}, {@code
 *       inputSchemaJson}, {@code rawUsageJson}) verbatim without re-encoding;
 *   <li>emit {@link java.math.BigDecimal} values as canonical {@code toPlainString} strings and
 *       sort {@code Set<Enum>} entries by enum name for cross-JVM determinism.
 * </ul>
 *
 * <p>No new dependency is introduced; only Jackson {@code JsonNode} primitives are used so callers
 * can rely on a stable wire contract without coupling to Provider SDK types.
 */
package fun.fengwk.kkstudio.harness.model.provider.codec;
