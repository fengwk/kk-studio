import { describe, expect, it } from 'vitest'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import {
  interactionIdentity,
  manualInteractions,
} from '@/shared/lib/interactions'

const owner = {
  kind: 'CHAT_SPACE',
  id: 'space-1',
  title: 'Workspace',
  link: '/chat-spaces/space-1',
}

const input: InteractionDTO = {
  type: 'INPUT',
  toolInvocationId: 'inv-1',
  threadId: 'th-1',
  sessionId: 'sess-1',
  rootThreadId: 'root-1',
  owner,
  toolCallId: 'call-1',
  toolName: 'ask_user',
  argumentsJson: '{}',
  createdAt: 1790503200000,
}

const approval: InteractionDTO = {
  ...input,
  type: 'APPROVAL',
  approval: {},
  queuedSameFileCount: 0,
}

const environmentWait = (rootThreadId: string, environmentId: string): InteractionDTO => ({
  type: 'ENVIRONMENT_WAIT',
  rootThreadId,
  owner,
  createdAt: 1790503200000,
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
