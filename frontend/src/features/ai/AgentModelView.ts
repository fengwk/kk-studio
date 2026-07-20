import type { AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'

/**
 * Client-side projection of {@link AgentModelDTO} enriched with the display fields the console
 * needs but the wire format deliberately omits. The only enriched field today is
 * {@link AgentModelView.providerName}, joined by {@link AgentModelDTO.providerId}.
 *
 * <p>This view is intentionally not exported through {@code shared/api/contracts.ts}; backend DTOs
 * stay minimal and discoverable, while feature-local projections stay close to the consumers that
 * actually use them.
 */
export interface AgentModelView extends AgentModelDTO {
  /** Display label for the model's provider; {@code null} when the provider was deleted. */
  providerName: string | null
}

/**
 * One helper, one join: every model is paired with the provider whose {@code id} matches its
 * {@code providerId}. Models without a matching provider keep {@link AgentModelView.providerName}
 * as {@code null} so callers can render a deleted/missing label without crashing.
 */
export function toAgentModelViews(
  models: AgentModelDTO[],
  providers: AgentProviderDTO[],
): AgentModelView[] {
  const byId = new Map(providers.map((provider) => [String(provider.id), provider]))
  return models.map((model): AgentModelView => {
    const provider = byId.get(String(model.providerId))
    return { ...model, providerName: provider?.name ?? null }
  })
}