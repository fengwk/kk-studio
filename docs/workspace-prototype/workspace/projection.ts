import type { QueuedThreadMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { CanvasResource } from '../canvas/CanvasResource'
import type { environmentActions } from '../mock/thread-data'

export type ApprovalDecision = 'ALLOW' | 'DENY'
export type ApprovalItem = {
  threadId: string
  invocationId: string
  command: string
  decide: (decision: ApprovalDecision, reason?: string) => void
}

type Message = { role: 'user' | 'agent'; text: string; turnEnd?: 'usage' | 'no-usage' | null; modelError?: string }
export type ConversationPane = {
  id: string
  node: HTMLElement
  targetKey: string
  kind: 'thread' | 'draft'
  visible: boolean
  draft: string
  agent?: string
  messages: Message[]
  queuedMessages: QueuedThreadMessage[]
  status: 'needs' | 'running' | 'waiting' | 'error' | 'idle'
  approval?: ApprovalItem
  setDraft: (text: string) => void
  send: (text: string) => string | null
  branch: (endIndex: number) => void
}

type CanvasPane = { id: string; node: HTMLElement; targetKey: string; kind: 'canvas'; canvas: CanvasResource }
export type EnvironmentView = (typeof environmentActions)[number]['view']
type EnvironmentPane = { id: string; node: HTMLElement; targetKey: string; kind: 'environment'; view: EnvironmentView }
type Snapshot = {
  panes: (ConversationPane | CanvasPane | EnvironmentPane)[]
  narrow: boolean
}

const preview = window as Window & { kkProjectView?: Snapshot }
export const readWorkspace = () => preview.kkProjectView ?? null
export function subscribeToWorkspace(update: () => void) {
  window.addEventListener('kk-project-view', update)
  return () => window.removeEventListener('kk-project-view', update)
}
