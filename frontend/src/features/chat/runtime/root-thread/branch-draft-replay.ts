import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import type { BranchDraft } from '@/features/chat/branch-draft'
import { parsePayload } from '@/features/chat/runtime/payload-json'
import { threadDraftPath } from '@/features/chat/thread-draft-path'


export function cloneDraft(draft: BranchDraft): BranchDraft {
  return {
    ...draft,
    model: { ...draft.model },
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
  const model = isRecord(settings.model) ? settings.model : null
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
    model: {
      providerName: typeof model?.providerName === 'string'
        ? model.providerName
        : fallback.model.providerName,
      modelName: typeof model?.modelName === 'string'
        ? model.modelName
        : fallback.model.modelName,
      variant: typeof model?.variant === 'string' ? model.variant : fallback.model.variant,
    },
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
