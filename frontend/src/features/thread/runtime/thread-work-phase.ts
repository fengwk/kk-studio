import type {
  HarnessActiveAssistantOutputDTO,
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

export type ThreadWorkPhase =
  | { kind: 'model-running' }
  | { kind: 'tool-running' }
  | { kind: 'materializing-result' }
  | { kind: 'next-step' }
  | {
    kind: 'waiting'
    reason: 'environment' | 'approval' | 'input' | 'exit-confirmation' | 'async-result'
    environmentName?: string
  }
  | { kind: 'none' }

export interface ThreadWorkSnapshot {
  thread: Pick<HarnessThreadDTO, 'status'> | null | undefined
  activeAssistantOutput: Pick<HarnessActiveAssistantOutputDTO, 'status' | 'resultEntryId'> | null
  entries: Pick<HarnessSessionEntryDTO, 'entryId'>[]
  toolInvocations: Pick<
    ToolInvocationDTO,
    'status' | 'waitingForEnvironment' | 'requiredEnvironmentName'
  >[]
}

/** Snapshot facts alone determine the strip copy and whether its dot may animate. */
export function deriveThreadWorkPhase(snapshot: ThreadWorkSnapshot): ThreadWorkPhase {
  if (snapshot.activeAssistantOutput?.status === 'RUNNING') {
    return { kind: 'model-running' }
  }

  if (snapshot.toolInvocations.some((tool) => tool.status === 'RUNNING')) {
    return { kind: 'tool-running' }
  }

  const output = snapshot.activeAssistantOutput
  if (
    output != null
    && ['SUCCEEDED', 'FAILED', 'CANCELLED', 'BLOCKED'].includes(output.status)
    && (
      output.resultEntryId == null
      || !snapshot.entries.some((entry) => entry.entryId === output.resultEntryId)
    )
  ) {
    return { kind: 'materializing-result' }
  }

  const waitingTool = snapshot.toolInvocations.find(
    (tool) => ['WAITING_APPROVAL', 'WAITING_INPUT'].includes(tool.status)
      || (tool.status === 'READY' && tool.waitingForEnvironment),
  )
  if (waitingTool?.status === 'WAITING_APPROVAL') {
    return { kind: 'waiting', reason: 'approval' }
  }
  if (waitingTool?.status === 'WAITING_INPUT') {
    return { kind: 'waiting', reason: 'input' }
  }
  if (waitingTool?.status === 'READY') {
    return {
      kind: 'waiting',
      reason: 'environment',
      environmentName: waitingTool.requiredEnvironmentName ?? '',
    }
  }

  if (snapshot.thread?.status === 'EXIT_PENDING') {
    return { kind: 'waiting', reason: 'exit-confirmation' }
  }

  if (
    output?.status === 'READY'
    || snapshot.toolInvocations.some((tool) => tool.status === 'READY')
    || snapshot.thread?.status === 'RUNNING'
  ) {
    return { kind: 'next-step' }
  }

  if (snapshot.thread?.status === 'WAITING') {
    return { kind: 'waiting', reason: 'async-result' }
  }

  return { kind: 'none' }
}
