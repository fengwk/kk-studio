const FREE_MODE_ERROR =
  'free E2E requires every catalog provider to be explicitly unconfigured '
  + '(configured=false, no baseUrl); use a fresh E2E database or pass --real'

/**
 * Enforce the paid-provider boundary before an E2E runner executes any case.
 *
 * Free mode fails closed for an empty/malformed catalog or any credentialed/routed Provider.
 * Real mode is explicitly authorized by the caller and is validated by its own seed contracts.
 */
export function assertProviderExecutionBoundary({ real, providers }) {
  if (real === true) return
  if (
    !Array.isArray(providers)
    || providers.length === 0
    || providers.some(
      (provider) =>
        provider === null
        || typeof provider !== 'object'
        || typeof provider.name !== 'string'
        || provider.name.length === 0
        || provider.configured !== false
        || (provider.baseUrl !== null && provider.baseUrl !== undefined),
    )
  ) {
    throw new Error(FREE_MODE_ERROR)
  }
}
