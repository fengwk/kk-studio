import type { BranchDraft } from '@/features/chat/branch-draft'
import { parsePayload } from '@/features/chat/runtime/payload-json'
import { threadDraftPath } from '@/features/chat/thread-draft-path'
import {
  createBuiltinExecution,
  getBuiltinModelSelection,
} from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'


export function cloneDraft(draft: BranchDraft): BranchDraft {
  return {
    ...draft,
    execution: {
      driver: draft.execution.driver,
      settings: { ...draft.execution.settings },
    },
  }
}

export function branchDraftFromEntry(
  entry: HarnessSessionEntryDTO,
  fallback: BranchDraft | null,
): BranchDraft | null {
  if (fallback == null) {
    return null
  }
  const payload = parsePayload(entry.payloadJson)
  const settings = isRecord(payload.settings) ? payload.settings : null
  if (settings == null) {
    return cloneDraft(fallback)
  }
  const execution = isRecord(settings.execution) ? settings.execution : null
  let nextExecution = fallback.execution
  if (execution != null) {
    const driver = typeof execution.driver === 'string' ? execution.driver : fallback.execution.driver
    if (driver === 'builtin') {
      const fallbackBuiltinModel = getBuiltinModelSelection(fallback.execution)
      const executionSettings = isRecord(execution.settings) ? execution.settings : null
      const modelName = typeof executionSettings?.modelName === 'string'
        ? executionSettings.modelName
        : (fallbackBuiltinModel?.modelName ?? '')
      const variant = typeof executionSettings?.variant === 'string'
        ? executionSettings.variant
        : executionSettings?.variant === null
          ? null
          : (fallbackBuiltinModel?.variant ?? null)
      nextExecution = createBuiltinExecution(modelName, variant)
    } else {
      nextExecution = {
        driver,
        settings: isRecord(execution.settings) ? { ...execution.settings } : { ...fallback.execution.settings },
      }
    }
  }
  let environmentName = fallback.environmentName
  if (Object.hasOwn(settings, 'environmentName')) {
    if (typeof settings.environmentName === 'string') {
      environmentName = settings.environmentName
    } else if (settings.environmentName === null) {
      environmentName = null
    }
  }
  return {
    ...cloneDraft(fallback),
    agentName: typeof settings.agentName === 'string' ? settings.agentName : fallback.agentName,
    execution: nextExecution,
    environmentName,
  }
}

export function branchDraftFromEntryPath(
  entries: HarnessSessionEntryDTO[],
  targetEntryId: string,
  fallback: BranchDraft | null,
): BranchDraft | null {
  if (fallback == null) {
    return null
  }
  const path = threadDraftPath(entries, entries.find((entry) => entry.entryId === targetEntryId)?.sessionId ?? '', targetEntryId)
  let draft = cloneDraft(fallback)
  for (const entry of path) {
    draft = branchDraftFromEntry(entry, draft) ?? draft
  }
  return draft
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
