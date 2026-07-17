import type { HarnessRunDTO } from '@/shared/api/contracts'
export { buildSessionTimeline } from '@/features/ai/session-timeline-builder'
export type {
  DialogueMessage,
  DialogueRole,
  DialogueStatus,
  RuntimeContext,
  SessionTimeline,
  TextDialogueMessage,
  ToolAttachment,
  ToolAttachmentType,
  ToolDialogueMessage,
} from '@/features/ai/session-event-types'

export function hasActiveRun(runs: HarnessRunDTO[]): boolean {
  return runs.some((run) => run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'WAITING_TOOLS')
}
