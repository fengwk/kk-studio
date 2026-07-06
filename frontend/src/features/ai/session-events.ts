import type { AgentRunDTO } from '@/shared/api/contracts'
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

export function hasActiveRun(runs: AgentRunDTO[]): boolean {
  return runs.some((run) => run.status === 'queued' || run.status === 'running')
}
