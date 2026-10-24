import { act, fireEvent, renderHook, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { paneTargetViewKey, threadIdentity, useThreadPresentation } from './thread-presentation'
import { ThreadPresentationActions } from './ThreadPresentationActions'
import type { AgentModelDTO, HarnessThreadDTO } from '@/shared/api/generated/contracts'

const thread: HarnessThreadDTO = {
  threadId: 'child', name: 'worker', sessionId: 'session', headEntryId: 'head',
  parentThreadId: 'parent', rootThreadId: 'root', yoloEnabled: false,
  nextCommandSequence: '1', version: '1', status: 'IDLE',
  executionControl: 'RUNNABLE', createTime: 0, updateTime: 0,
  branchSettings: {
    agentName: 'researcher', model: { modelName: 'model', variant: 'deep' },
    environmentName: null, goal: null,
  },
}
const model: AgentModelDTO = {
  providerName: 'provider', kind: 'MODEL', name: 'model', modelId: 'actual-model', description: null,
  version: '1', createTime: 0, updateTime: 0,
  config: {
    limit: { context: 10000, output: 1000 },
    abilities: { tools: true, reasoning: true, inputModalities: ['TEXT'] },
    defaultVariant: 'deep', variants: [{ id: 'deep', reasoningEffort: 'high' }],
    pricing: {
      base: { input: '1', output: '1', cacheRead: '1', cacheWrite: '1', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
  },
}

function options() {
  return {
    views: { viewKey: 'thread:child', mode: 'conversation' as 'conversation' | 'debug', switchMode: vi.fn() },
    projection: { thread: thread as HarnessThreadDTO | undefined, models: [model] },
    threadId: 'child', selected: true, active: true, onReport: vi.fn(),
    onParent: vi.fn(),
  }
}

describe('visible thread presentation', () => {
  it('uses durable draft identities without inventing a thread', () => {
    expect(paneTargetViewKey({ kind: 'NEW_SESSION_DRAFT' })).toBe('')
    expect(paneTargetViewKey({ kind: 'BOUND_THREAD', threadId: 'child' })).toBe('thread:child')
    expect(paneTargetViewKey({ kind: 'NEW_THREAD_DRAFT', sessionId: 's', startEntryId: 'e', threadName: 'n' })).toBe('branch:s:e:n')
    expect(paneTargetViewKey({ kind: 'FORK_SESSION_DRAFT', sessionId: 's', sourceThreadId: 't', startEntryId: 'e' })).toBe('fork-session:s:t:e')
  })

  it('uses the real branch variant as identity without consulting catalog reasoning facts', () => {
    // 身份只来自 branch settings 快照，catalog 未加载（无 models）也不改变结果。
    expect(threadIdentity({ thread })).toBe('researcher · model (deep)')
    expect(threadIdentity({ thread: undefined })).toBeNull()
    // 在运行时边界注入非法 null，验证空值保护。
    expect(Reflect.apply(threadIdentity, undefined, [{ thread: null }])).toBeNull()
    // variant 全空白时只显示规范 model ref，不伪造 default variant，也不换算 reasoningEffort。
    expect(threadIdentity({
      thread: {
        ...thread,
        branchSettings: {
          ...thread.branchSettings,
          model: { modelName: 'model', variant: '  ' },
        },
      },
    })).toBe('researcher · model')
  })

  it('reports only selected views and keeps callbacks stable without render loops', () => {
    const initial = options()
    const { result, rerender } = renderHook(useThreadPresentation, { initialProps: initial })
    const report = result.current
    expect(initial.onReport).toHaveBeenCalledTimes(1)
    rerender({ ...initial, onReport: vi.fn() })
    expect(result.current).toBe(report)
    expect(initial.onReport).toHaveBeenCalledTimes(1)
    rerender({ ...initial, selected: false, views: { ...initial.views, mode: 'debug' } })
    expect(initial.onReport).toHaveBeenCalledTimes(1)
    act(() => report.act(report.viewKey, 'parent'))
    expect(initial.onParent).not.toHaveBeenCalled()
  })

  it('scopes stable actions to the latest committed key, active layer and actual parent', () => {
    const initial = options()
    const restore = vi.fn()
    const { result, rerender } = renderHook(useThreadPresentation, { initialProps: { ...initial, onRestoreFocus: restore } })
    const action = result.current.act
    act(() => {
      action('stale', 'debug')
      action('thread:child', 'debug')
      action('thread:child', 'parent')
      action('thread:child', 'close-debug')
    })
    expect(initial.views.switchMode.mock.calls).toEqual([['debug'], ['conversation']])
    expect(initial.onParent).toHaveBeenCalledWith('parent')
    expect(restore).not.toHaveBeenCalled()
    act(() => action('thread:child', 'restore-focus'))
    expect(restore).toHaveBeenCalledOnce()
    const next = { ...initial, onRestoreFocus: restore, active: false }
    rerender(next)
    act(() => action('thread:child', 'debug'))
    expect(initial.views.switchMode).toHaveBeenCalledTimes(2)
    rerender({ ...next, active: true, views: { ...initial.views, viewKey: 'thread:new' } })
    act(() => action('thread:child', 'parent'))
    expect(initial.onParent).toHaveBeenCalledOnce()
    expect(result.current.act).toBe(action)
  })

  it('does not pass an unconfirmed child off as the hidden root, but supports draft Debug', () => {
    const initial = options()
    const { result, rerender } = renderHook(useThreadPresentation, { initialProps: initial })
    rerender({ ...initial, threadId: 'unknown' })
    expect(result.current).toMatchObject({ threadId: null, name: null, identity: null, parentThreadId: null })
    act(() => {
      result.current.act('thread:child', 'debug')
      result.current.act('thread:child', 'parent')
    })
    expect(initial.views.switchMode).not.toHaveBeenCalled()
    expect(initial.onParent).not.toHaveBeenCalled()
    rerender({ ...initial, threadId: '', projection: { thread: undefined, models: [] } })
    act(() => result.current.act('thread:child', 'debug'))
    expect(initial.views.switchMode).toHaveBeenCalledWith('debug')
  })

  it('restores readonly trigger focus after Debug closes and renders a real direct-parent link', async () => {
    setLocale('zh-CN')
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const initial = { ...options(), onParent: undefined }
    const { result, rerender } = renderHook(useThreadPresentation, { initialProps: initial })
    act(() => result.current.act('thread:child', 'debug'))
    rerender({ ...initial, views: { ...initial.views, mode: 'debug' } })
    trigger.blur()
    act(() => result.current.act('thread:child', 'close-debug'))
    rerender(initial)
    expect(trigger).not.toHaveFocus()
    act(() => result.current.act('thread:child', 'restore-focus'))
    await waitFor(() => expect(trigger).toHaveFocus())
    expect(result.current.parentHref).toBe('/threads/parent')
    render(<MemoryRouter><ThreadPresentationActions view={result.current} /></MemoryRouter>)
    expect(screen.getByRole('link', { name: '回到父 agent' })).toHaveAttribute('href', '/threads/parent')
    expect(screen.getByText('researcher · model (deep)')).toHaveAttribute('title', 'researcher · model (deep)')
    trigger.remove()
  })

  it('does not consume readonly restore intent before the host confirms visibility or after disposal', () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    const initial = { ...options(), onParent: undefined }
    const { result, rerender, unmount } = renderHook(useThreadPresentation, { initialProps: initial })
    const action = result.current.act
    act(() => action('thread:child', 'debug', trigger))
    rerender({ ...initial, views: { ...initial.views, mode: 'debug' } })
    act(() => {
      action('thread:child', 'close-debug')
      action('thread:child', 'restore-focus')
    })
    expect(trigger).not.toHaveFocus()
    rerender(initial)
    act(() => action('thread:child', 'restore-focus'))
    expect(trigger).toHaveFocus()
    trigger.blur()
    unmount()
    act(() => action('thread:child', 'debug', trigger))
    expect(initial.views.switchMode).toHaveBeenCalledTimes(2)
    trigger.remove()
  })

  it('omits a child default main name and uses its actual agent identity', () => {
    const initial = options()
    const { result, rerender } = renderHook(useThreadPresentation, { initialProps: {
      ...initial, projection: { ...initial.projection, thread: { ...thread, name: 'main' } },
    } })
    expect(result.current.name).toBeNull()
    expect(result.current.identity).toBe('researcher · model (deep)')
    rerender({ ...initial, projection: { models: [], thread: { ...thread, name: 'main' } } })
    expect(result.current.identity).toBe('researcher · model (deep)')
  })

  it('offers only direct-parent navigation, never topbar Debug or subagent actions', () => {
    setLocale('zh-CN')
    const initial = options()
    const { result } = renderHook(useThreadPresentation, { initialProps: initial })
    const host = render(<ThreadPresentationActions view={null} />)
    expect(screen.queryByRole('button')).toBeNull()
    host.rerender(<ThreadPresentationActions view={result.current} />)
    fireEvent.click(screen.getByRole('button', { name: '回到父 agent' }))
    expect(initial.onParent).toHaveBeenCalledWith('parent')
    expect(initial.views.switchMode).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '查看 subagent 执行' })).toBeNull()
    host.rerender(<ThreadPresentationActions view={{ ...result.current, mode: 'debug' }} />)
    expect(screen.queryByRole('button', { name: 'Debug' })).toBeNull()
    expect(document.querySelector('.workspace-read-actions')).toBeNull()
    host.rerender(<ThreadPresentationActions view={{
      ...result.current, viewKey: '', threadId: null, identity: null, parentThreadId: null,
    }} />)
    expect(screen.queryByRole('button', { name: 'Debug' })).toBeNull()
    expect(screen.queryByRole('button', { name: '查看 subagent 执行' })).toBeNull()
    expect(document.querySelector('.workspace-view-identity')).toBeNull()
  })
})
