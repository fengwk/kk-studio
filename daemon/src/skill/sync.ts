import {
  type CapabilityCall,
  type CapabilityContext,
  type CapabilityExecutor,
  type CapabilityResult,
  type DaemonConfig,
  errorResult,
} from '../contracts.js'
import { SkillPackageInstaller } from './installer.js'
import { SkillSyncException } from './types.js'
import { isAbortError } from './http.js'

export function createSkillCapability(
  config: DaemonConfig,
  installer?: SkillPackageInstaller,
): CapabilityExecutor {
  const activeInstaller = installer ?? SkillPackageInstaller.open(config)

  return async (call: CapabilityCall, context: CapabilityContext): Promise<CapabilityResult> => {
    const callId = call.invocationId

    if (context.signal.aborted) {
      return errorResult(callId, 'Operation cancelled')
    }

    try {
      const args = call.arguments
      if (!args || typeof args !== 'object' || Array.isArray(args)) {
        return errorResult(callId, 'Invalid skill sync argument', 'INVALID_ARGUMENT')
      }

      const { packageName, repositoryUrl, branch, targetCommit, credential } = args as Record<
        string,
        unknown
      >

      if (
        typeof packageName !== 'string' ||
        typeof repositoryUrl !== 'string' ||
        typeof branch !== 'string' ||
        typeof targetCommit !== 'string'
      ) {
        return errorResult(callId, 'Invalid skill sync argument', 'INVALID_ARGUMENT')
      }

      let parsedCredential: string | null = null
      if (credential !== undefined && credential !== null) {
        if (typeof credential !== 'string') {
          return errorResult(callId, 'Invalid skill sync argument', 'INVALID_ARGUMENT')
        }
        parsedCredential = credential.trim().length > 0 ? credential.trim() : null
      }

      const installed = await activeInstaller.install(
        packageName,
        repositoryUrl,
        branch,
        targetCommit,
        parsedCredential,
        context.signal,
      )

      return {
        callId,
        error: false,
        details: {},
        contents: [
          {
            type: 'json',
            json: {
              packageName: installed.packageName,
              installedCommit: installed.installedCommit,
              localPath: installed.localPath,
            },
          },
        ],
      }
    } catch (error) {
      if (context.signal.aborted || isAbortError(error)) {
        return errorResult(callId, 'Operation cancelled')
      }
      if (error instanceof SkillSyncException) {
        return errorResult(callId, error.message, error.code)
      }
      return errorResult(callId, 'Skill sync failed', 'SKILL_SYNC_FAILED')
    }
  }
}
