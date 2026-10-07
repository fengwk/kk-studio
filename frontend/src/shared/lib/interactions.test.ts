import { describe, expect, it } from 'vitest'
import type {
  ApprovalInteractionDTO,
  EnvironmentWaitInteractionDTO,
  InputInteractionDTO,
} from '@/shared/api/contracts/ai-interaction'
import { interactionIdentity, manualInteractions } from '@/shared/lib/interactions'

const owner = {
  type: 'CHAT' as const,
  chatId: 'chat-1',
  chatTitle: null,
  issueId: null,
  issueTitle: null,
  agentName: null,
  rootThreadName: null,
}

const input: InputInteractionDTO = {
  type: 'INPUT',
  interactionId: 'inv-1',
  status: 'WAITING_INPUT',
  threadId: 'th-1',
  sessionId: 'sess-1',
  rootThreadId: 'root-1',
  owner,
  toolCallId: 'call-1',
  toolName: 'ask_user',
  argumentsJson: '{}',
  approvalJson: null,
  environmentId: null,
  environmentName: null,
  waitingCount: null,
  createTime: '2026-09-27T10:00:00Z',
}

const approval: ApprovalInteractionDTO = {
  ...input,
  type: 'APPROVAL',
  status: 'WAITING_APPROVAL',
  approvalJson: '{}',
}

const environmentWait = (rootThreadId: string, environmentId: string): EnvironmentWaitInteractionDTO => ({
  type: 'ENVIRONMENT_WAIT',
  rootThreadId,
  owner,
  createTime: '2026-09-27T10:00:00Z',
  interactionId: null,
  status: null,
  threadId: null,
  sessionId: null,
  toolCallId: null,
  toolName: null,
  argumentsJson: null,
  approvalJson: null,
  environmentId,
  environmentName: 'archlinux',
  waitingCount: 2,
})

describe('interactionIdentity', () => {
  it('人工等待按原始调用主键区分', () => {
    expect(interactionIdentity(input)).toBe('invocation:inv-1')
  })

  it('环境等待按 (真实执行根, 冻结环境) 区分，两个 null 主键不合并', () => {
    const first = interactionIdentity(environmentWait('root-1', 'env-a'))
    const second = interactionIdentity(environmentWait('root-2', 'env-a'))
    const third = interactionIdentity(environmentWait('root-1', 'env-b'))
    expect(new Set([first, second, third]).size).toBe(3)
  })
})

describe('manualInteractions', () => {
  it('只保留可操作的人工等待，环境等待交给全局待处理页与工具状态行', () => {
    expect(manualInteractions([input, environmentWait('root-1', 'env-a'), approval])).toEqual([
      input,
      approval,
    ])
  })

  it('全部是环境等待时返回空列表（根面板不渲染第二张提醒卡）', () => {
    expect(manualInteractions([environmentWait('root-1', 'env-a')])).toEqual([])
  })
})
