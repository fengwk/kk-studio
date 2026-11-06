import { describe, expect, it } from 'vitest'
import { setLocale } from '@/shared/i18n'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import {
  interactionIdentity,
  interactionOwnerLabel,
  interactionOwnerLink,
  manualInteractions,
} from '@/shared/lib/interactions'

const owner = {
  kind: 'CHAT',
  id: 'chat-1',
  title: null,
  link: '/chats/chat-1',
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

describe('interactionOwnerLabel', () => {
  it('归属固定为“类型 · 标题”，标题来自会话归属 SPI', () => {
    expect(interactionOwnerLabel({ ...owner, title: '产品对话' })).toBe('Chat · 产品对话')
    setLocale('en-US')
    expect(interactionOwnerLabel({ ...owner, kind: 'ISSUE_AGENT', title: '修复缺陷' }))
      .toBe('Issue · 修复缺陷')
    expect(interactionOwnerLabel({ ...owner, kind: 'AGENT_TASK', title: 'reporter' }))
      .toBe('Agent task · reporter')
    setLocale('zh-CN')
  })

  it('标题缺失时只显示类型，未知类型不猜测产品名', () => {
    setLocale('zh-CN')
    expect(interactionOwnerLabel(owner)).toBe('Chat')
    expect(interactionOwnerLabel({ ...owner, kind: 'FUTURE_OWNER', title: ' ' })).toBe('FUTURE_OWNER')
  })
})

describe('interactionOwnerLink', () => {
  it('产品归属返回应用内路径，AGENT_TASK 永远不跳转', () => {
    expect(interactionOwnerLink(owner)).toBe('/chats/chat-1')
    expect(interactionOwnerLink({ ...owner, kind: 'AGENT_TASK', link: '/threads/root-1' })).toBeNull()
    expect(interactionOwnerLink({ ...owner, link: null })).toBeNull()
  })

  it('协议相对地址、反斜杠与控制字符都不采纳', () => {
    expect(interactionOwnerLink({ ...owner, link: '//example.com/x' })).toBeNull()
    expect(interactionOwnerLink({ ...owner, link: 'https://example.com/x' })).toBeNull()
    expect(interactionOwnerLink({ ...owner, link: '/x\\y' })).toBeNull()
    expect(interactionOwnerLink({ ...owner, link: '/x\n/y' })).toBeNull()
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
