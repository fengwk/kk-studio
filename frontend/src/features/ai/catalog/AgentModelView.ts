import type { AgentModelDTO } from '@/shared/api/contracts/ai-catalog'

/**
 * Client-side projection of {@link AgentModelDTO} enriched with the display fields the console
 * needs but the wire format deliberately omits. The only enriched field today is
 * {@link AgentModelView.providerName}, carried directly by the model identity.
 *
 * <p>This view is intentionally not exported through {@code shared/api/contracts/ai-catalog.ts};
 * backend DTOs stay minimal and discoverable, while feature-local projections stay close to the
 * consumers that actually use them.
 */
export interface AgentModelView extends AgentModelDTO {
  /** Canonical provider identity owned by this model. */
  providerName: string
}

/**
 * Canonical human-facing model identity: {@code provider/model}.
 *
 * <p>This is the unique display/reference label used by cards, selectors, footer and confirmations.
 */
export function formatModelRef(
  providerName: string | null | undefined,
  modelName: string | null | undefined,
): string {
  const provider = providerName?.trim() ?? ''
  const model = modelName?.trim() ?? ''
  if (provider && model) {
    // Avoid double-prefix when callers already pass a composed ref.
    if (model === provider || model.startsWith(`${provider}/`)) {
      return model
    }
    return `${provider}/${model}`
  }
  return model || provider || 'unknown-model'
}

/** Convenience for {@link AgentModelView}. */
export function modelRef(
  model: Pick<AgentModelView, 'name' | 'providerName'>,
): string {
  return formatModelRef(model.providerName, model.name)
}

/**
 * Models already carry their canonical provider name; keep a feature-local view for callers that
 * also need model-specific helpers without reintroducing a resource-id join.
 */
export function toAgentModelViews(models: AgentModelDTO[]): AgentModelView[] {
  return models.map((model) => ({ ...model }))
}