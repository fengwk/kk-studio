import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { ThreadNavigationContext } from '@/features/thread/runtime/panes/thread-navigation-context'
import { InteractionOwnerSource } from './InteractionOwnerSource'
import type { InteractionDTO } from '@/shared/api/generated/contracts'

const childInvocation: InteractionDTO = {
  type: 'APPROVAL',
  toolInvocationId: 'inv-1',
  threadId: '0f3c9a2e-1111-4000-8000-000000000001',
  rootThreadId: '9a2e0f3c-2222-4000-8000-000000000002',
  sessionId: 'session-1',
  owner: { kind: 'CHAT_SPACE', id: 'space-1', title: '产品工作区', link: '/chat-spaces/space-1' },
  toolCallId: 'call-1',
  toolName: 'bash',
  argumentsJson: '{"command":"ls"}',
  approval: { required: true },
  queuedSameFileCount: 0,
  createdAt: 1790503200000,
}

function renderSource(item: InteractionDTO, openThread?: (threadId: string) => void) {
  return render(
    <ThreadNavigationContext.Provider value={openThread ?? vi.fn()}>
      <InteractionOwnerSource item={item} />
    </ThreadNavigationContext.Provider>,
  )
}

describe('interaction owner source', () => {
  it('adds a subagent entry without exposing the child thread id', () => {
    // 测试意图：子线程调用只展示原生按钮入口，不暴露 UUID 或旧归属按钮。
    setLocale('zh-CN')
    renderSource(childInvocation)

    const button = screen.getByRole('button', { name: '打开子 Agent' })
    expect(button).toHaveAttribute('title', '打开子 Agent')
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toHaveTextContent('子 Agent')
    expect(button).not.toHaveTextContent('0f3c9a2e')
  })

  it('opens the subagent in the current pane when the pane owns thread navigation', () => {
    // 测试意图：子 Agent 来源按钮通过 pane 导航回调打开子线程。
    setLocale('zh-CN')
    const openThread = vi.fn()
    renderSource(childInvocation, openThread)

    const button = screen.getByRole('button', { name: '打开子 Agent' })
    expect(button).toHaveAttribute('type', 'button')
    fireEvent.click(button)
    expect(openThread).toHaveBeenCalledWith('0f3c9a2e-1111-4000-8000-000000000001')
  })

  it('hides the subagent action when pane navigation is not installed', () => {
    setLocale('zh-CN')
    render(<InteractionOwnerSource item={childInvocation} />)
    expect(screen.queryByRole('button', { name: '打开子 Agent' })).not.toBeInTheDocument()
  })

  it('shows no subagent entry for invocations owned by the execution root', () => {
    setLocale('zh-CN')
    renderSource({ ...childInvocation, threadId: childInvocation.rootThreadId })
    expect(screen.queryByRole('button', { name: '打开子 Agent' })).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows no subagent entry for aggregated environment waits', () => {
    // 测试意图：环境等待是执行根聚合项，没有调用线程，不能凭空补来源入口。
    setLocale('zh-CN')
    renderSource({
      type: 'ENVIRONMENT_WAIT',
      rootThreadId: childInvocation.rootThreadId,
      owner: childInvocation.owner,
      environmentId: 'env-1',
      environmentName: 'archlinux',
      waitingCount: 2,
      createdAt: 1790503200000,
    })
    expect(screen.queryByRole('button', { name: '打开子 Agent' })).toBeNull()
  })

  it('still offers the subagent entry when owner is AGENT_TASK', () => {
    setLocale('zh-CN')
    renderSource({
      ...childInvocation,
      owner: { kind: 'AGENT_TASK', id: 'task-1', title: 'reporter', link: null },
    })
    expect(screen.getByRole('button', { name: '打开子 Agent' })).toHaveAttribute('type', 'button')
  })

  it('localizes the subagent entry in English', () => {
    setLocale('en-US')
    renderSource(childInvocation)
    expect(screen.getByRole('button', { name: 'Open subagent' })).toHaveTextContent('subagent')
  })
})
