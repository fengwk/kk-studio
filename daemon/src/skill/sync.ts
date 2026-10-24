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

    try {
      if (context.signal.aborted) {
        return errorResult(callId, 'Operation cancelled')
      }
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
    } finally {
      // install 返回或抛出后没有后台任务。返回前确认，避免调用方在 await 返回后看到未确认。
      context.confirmExecutionExit()
    }
  }
}
