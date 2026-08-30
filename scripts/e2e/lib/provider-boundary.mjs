const SEED_PROVIDER_NAME = 'minimax'
const FREE_MODE_ERROR =
  'free E2E requires seed provider minimax to be explicitly unconfigured '
  + '(configured=false, no baseUrl); use a fresh E2E database or pass --real'

/**
 * Enforce the paid-provider boundary before an E2E runner executes any case.
 *
 * Free mode fails closed for a missing, malformed, credentialed, or routed seed Provider.
 * Real mode is explicitly authorized by the caller and is validated by its own seed contracts.
 */
export function assertProviderExecutionBoundary({ real, providers }) {
  if (real === true) return
  if (!Array.isArray(providers)) throw new Error(FREE_MODE_ERROR)
  const provider = providers.find((candidate) => candidate?.name === SEED_PROVIDER_NAME)
  if (
    !provider
    || provider.configured !== false
    || (provider.baseUrl !== null && provider.baseUrl !== undefined)
  ) {
    throw new Error(FREE_MODE_ERROR)
  }
}
