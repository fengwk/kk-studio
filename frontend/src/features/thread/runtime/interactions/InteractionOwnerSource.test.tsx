import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { ThreadNavigationContext } from '@/features/thread/runtime/thread-navigation-context'
import { InteractionOwnerSource } from './InteractionOwnerSource'
import type { InteractionDTO } from '@/shared/api/generated/contracts'

const childInvocation: InteractionDTO = {
  type: 'APPROVAL',
  toolInvocationId: 'inv-1',
  threadId: '0f3c9a2e-1111-4000-8000-000000000001',
  rootThreadId: '9a2e0f3c-2222-4000-8000-000000000002',
  sessionId: 'session-1',
  owner: { kind: 'CHAT', id: 'chat-1', title: '产品对话', link: '/chats/chat-1' },
  toolCallId: 'call-1',
  toolName: 'bash',
  argumentsJson: '{"command":"ls"}',
  approval: { required: true },
  queuedSameFileCount: 0,
  createdAt: 1790503200000,
}

function renderSource(item: InteractionDTO, openThread?: (threadId: string) => void) {
  const source = <InteractionOwnerSource item={item} />
  return render(
    <MemoryRouter>
      {openThread ? (
        <ThreadNavigationContext.Provider value={openThread}>{source}</ThreadNavigationContext.Provider>
      ) : source}
    </MemoryRouter>,
  )
}

describe('interaction owner source', () => {
  it('adds a subagent entry after the owner without exposing the child thread id', () => {
    // 测试意图：子线程调用在归属后补“subagent”入口，链接目标是子线程地址而非执行根。
    setLocale('zh-CN')
    renderSource(childInvocation)

    const link = screen.getByRole('link', { name: '打开 subagent' })
    expect(link).toHaveAttribute('title', '打开 subagent')
    expect(link).toHaveAttribute('href', '/threads/0f3c9a2e-1111-4000-8000-000000000001')
    expect(link).toHaveTextContent('subagent')
    expect(link).not.toHaveTextContent('0f3c9a2e')
    expect(screen.getByRole('button', { name: 'Chat · 产品对话' })).toHaveAttribute('title', '查看来源')
  })

  it('opens the subagent in the current pane when the pane owns thread navigation', () => {
    // 测试意图：根面板由 ThreadLink 复用 pane 导航，不再整页跳转。
    setLocale('zh-CN')
    const openThread = vi.fn()
    renderSource(childInvocation, openThread)

    fireEvent.click(screen.getByRole('link', { name: '打开 subagent' }))
    expect(openThread).toHaveBeenCalledWith('0f3c9a2e-1111-4000-8000-000000000001')
  })

  it('shows no subagent entry for invocations owned by the execution root', () => {
    setLocale('zh-CN')
    renderSource({ ...childInvocation, threadId: childInvocation.rootThreadId })
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByRole('button', { name: 'Chat · 产品对话' })).toBeInTheDocument()
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
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('keeps a headless owner as text and still offers the subagent entry', () => {
    setLocale('zh-CN')
    renderSource({
      ...childInvocation,
      owner: { kind: 'AGENT_TASK', id: 'task-1', title: 'reporter', link: null },
    })
    expect(screen.getByText('Agent 任务 · reporter').closest('button')).toBeNull()
    expect(screen.getByRole('link', { name: '打开 subagent' }))
      .toHaveAttribute('href', '/threads/0f3c9a2e-1111-4000-8000-000000000001')
  })

  it('localizes the subagent entry in English', () => {
    setLocale('en-US')
    renderSource(childInvocation)
    expect(screen.getByRole('link', { name: 'Open subagent' })).toHaveTextContent('subagent')
  })
})
