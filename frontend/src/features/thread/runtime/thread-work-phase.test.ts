import { describe, expect, it } from 'vitest'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessThreadDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'
import { deriveThreadWorkPhase } from '@/features/thread/runtime/thread-work-phase'

function phase({
  threadStatus = 'IDLE',
  outputStatus,
  resultEntryId = null,
  toolStatus,
  waitingForEnvironment = false,
  requiredEnvironmentName = null,
}: {
  threadStatus?: HarnessThreadDTO['status']
  outputStatus?: HarnessActiveAssistantOutputDTO['status']
  resultEntryId?: string | null
  toolStatus?: ToolInvocationDTO['status']
  waitingForEnvironment?: boolean
  requiredEnvironmentName?: string | null
} = {}) {
  return deriveThreadWorkPhase({
    thread: { status: threadStatus },
    activeAssistantOutput: outputStatus == null ? null : { status: outputStatus, resultEntryId },
    entries: [],
    toolInvocations: toolStatus == null
      ? []
      : [{ status: toolStatus, waitingForEnvironment, requiredEnvironmentName }],
  })
}

describe('deriveThreadWorkPhase', () => {
  it('derives distinct running, materializing, scheduled, waiting and idle phases from snapshot facts', () => {
    // 意图：各快照阶段必须有唯一的展示事实，避免把泛化 Thread 状态误当成模型生成。
    expect(phase({ outputStatus: 'RUNNING', threadStatus: 'RUNNING' })).toEqual({ kind: 'model-running' })
    expect(phase({ toolStatus: 'RUNNING' })).toEqual({ kind: 'tool-running' })
    expect(phase({ outputStatus: 'SUCCEEDED' })).toEqual({ kind: 'materializing-result' })
    expect(phase({ outputStatus: 'READY' })).toEqual({ kind: 'next-step' })
    expect(phase({ threadStatus: 'WAITING' })).toEqual({ kind: 'waiting', reason: 'async-result' })
    expect(phase()).toEqual({ kind: 'none' })
  })

  it('animates only own model and tool execution, never a waiting or scheduled obligation', () => {
    // 意图：动画本身仅允许由自身执行状态产生，不把等待、排队或旧线程状态呈现为在线运行。
    const phases = [
      phase({ outputStatus: 'RUNNING' }),
      phase({ toolStatus: 'RUNNING' }),
      phase({ outputStatus: 'SUCCEEDED' }),
      phase({ threadStatus: 'RUNNING' }),
      phase({ threadStatus: 'WAITING' }),
    ]
    expect(phases.map((phase) => phase.kind === 'model-running' || phase.kind === 'tool-running'))
      .toEqual([true, true, false, false, false])
  })

  it('preserves concrete approval, input, environment and exit wait reasons', () => {
    // 意图：所有阻塞原因都静态展示真实等待对象，而非用泛化“运行中”替代。
    expect(phase({ toolStatus: 'WAITING_APPROVAL' })).toEqual({ kind: 'waiting', reason: 'approval' })
    expect(phase({ toolStatus: 'WAITING_INPUT' })).toEqual({ kind: 'waiting', reason: 'input' })
    expect(phase({
      toolStatus: 'READY',
      waitingForEnvironment: true,
      requiredEnvironmentName: 'archlinux',
    })).toEqual({
      kind: 'waiting',
      reason: 'environment',
      environmentName: 'archlinux',
    })
    expect(phase({ threadStatus: 'EXIT_PENDING' })).toEqual({ kind: 'waiting', reason: 'exit-confirmation' })
  })
})
