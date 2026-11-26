import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import { copyTextToClipboard } from '@/shared/lib/clipboard'
import { threadIdentity, useThreadPresentation } from './thread-presentation'
import { ThreadPresentationActions } from './ThreadPresentationActions'
import type { HarnessThreadDTO } from '@/shared/api/generated/contracts'

vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn() }))

const thread: HarnessThreadDTO = {
  threadId: 'child', name: 'worker', sessionId: 'session', headEntryId: 'head',
  parentThreadId: 'parent', rootThreadId: 'root', yoloEnabled: false,
  nextCommandSequence: '1', version: '1', status: 'IDLE',
  executionControl: 'RUNNABLE', createdAt: 0, updatedAt: 0,
  branchSettings: {
    agentName: 'researcher', execution: createBuiltinExecution('model', 'deep'),
    environmentName: null,
  },
}

function options(onParent = vi.fn(), active = true) {
  return {
    projection: { thread },
    threadId: 'child', active, onParent,
  }
}

describe('child thread presentation', () => {
  it('uses the actual branch model variant as identity, independent of a loaded catalog', () => {
    expect(threadIdentity({ thread })).toBe('researcher · model (deep)')
    expect(threadIdentity({ thread: undefined })).toBeNull()
    expect(Reflect.apply(threadIdentity, undefined, [{ thread: null }])).toBeNull()
    expect(threadIdentity({
      thread: {
        ...thread,
        branchSettings: {
          ...thread.branchSettings,
          execution: createBuiltinExecution('model', '  '),
        },
      },
    })).toBe('researcher · model')
  })

  it('only invokes the latest parent callback for the current active thread view', () => {
    const initialCallback = vi.fn()
    const { result, rerender } = renderHook(useThreadPresentation, {
      initialProps: options(initialCallback),
    })
    const action = result.current.act
    act(() => action('stale', 'parent'))
    act(() => action('child', 'parent'))
    expect(initialCallback).toHaveBeenCalledExactlyOnceWith('parent')

    const inactiveCallback = vi.fn()
    rerender({ ...options(inactiveCallback, false) })
    act(() => action('child', 'parent'))
    expect(inactiveCallback).not.toHaveBeenCalled()

    const changedCallback = vi.fn()
    rerender({ ...options(changedCallback), threadId: 'other' })
    act(() => action('child', 'parent'))
    expect(changedCallback).not.toHaveBeenCalled()
    expect(result.current.act).toBe(action)
  })

  it('uses a button for parent navigation and preserves child identity and copy behavior', async () => {
    setLocale('zh-CN')
    const threadId = '0f3c9a2e-1111-4000-8000-000000000001'
    const onParent = vi.fn()
    const initial = {
      ...options(onParent),
      threadId,
      projection: { thread: { ...thread, threadId } },
    }
    const { result } = renderHook(useThreadPresentation, { initialProps: initial })
    render(<ThreadPresentationActions view={result.current} />)

    expect(screen.getByText('0f3c9a2e')).toHaveAttribute('title', threadId)
    expect(document.body.textContent).not.toContain('1111-4000')
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByRole('button', { name: '回到父 Agent' })).toBeInTheDocument()
    expect(screen.getByText('researcher · model (deep)')).toHaveAttribute('title', 'researcher · model (deep)')

    fireEvent.click(screen.getByRole('button', { name: '回到父 Agent' }))
    expect(onParent).toHaveBeenCalledExactlyOnceWith('parent')

    vi.mocked(copyTextToClipboard).mockResolvedValue(true)
    fireEvent.click(screen.getByRole('button', { name: '复制 Thread ID' }))
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledWith(
      threadId, { shouldFallback: expect.any(Function) },
    ))
  })

  it('does not offer child identity or parent navigation for a root thread', () => {
    const rootThread = { ...thread, parentThreadId: null }
    const { result } = renderHook(useThreadPresentation, { initialProps: {
      ...options(), projection: { thread: rootThread },
    } })
    render(<ThreadPresentationActions view={result.current} />)
    expect(document.querySelector('.workspace-view-short-id')).toBeNull()
    expect(screen.queryByRole('button', { name: '复制 Thread ID' })).toBeNull()
    expect(screen.queryByRole('button', { name: '回到父 Agent' })).toBeNull()
  })
})
