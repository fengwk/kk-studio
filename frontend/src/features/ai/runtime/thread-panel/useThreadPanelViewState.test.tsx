import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { useThreadPanelViewState } from './useThreadPanelViewState'

function event(id: string, overrides: Partial<ThreadEventRecord> = {}): ThreadEventRecord {
  return {
    id, source: 'entry', entryId: id, turnStartEntryId: null, turnNumber: 0,
    kind: 'ASSISTANT_MESSAGE', status: 'completed', title: id, summary: id,
    createdAt: null, details: [], rawJson: null, ...overrides,
  }
}

function setup(events: ThreadEventRecord[] = []) {
  const transcript = { current: document.createElement('div') }
  const hook = renderHook(
    ({ key, events }) => useThreadPanelViewState(key, transcript, events),
    { initialProps: { key: 'pane:a', events } },
  )
  return { ...hook, transcript }
}

describe('Debug selection belongs to the local view identity', () => {
  it('selects the latest eligible model output rather than a newer boundary or synthetic overlay', () => {
    // metadata 准入包含 COMPACTION，不按 ASSISTANT kind 猜测。
    const { result } = setup([
      event('assistant', { historicalPreviewEligible: true }),
      event('compaction', { kind: 'COMPACTION', historicalPreviewEligible: true }),
      event('end', { kind: 'TURN_END' }),
      event('live', { source: 'active-model', entryId: null, historicalPreviewEligible: true }),
    ])
    expect(result.current.selectedEventId).toBeNull()
    act(() => result.current.switchMode('debug'))
    expect(result.current.selectedEventId).toBe('compaction')
  })

  it('falls back to the latest durable entry, never to synthetic running state', () => {
    const { result } = setup([
      event('user', { kind: 'USER_MESSAGE' }),
      event('end', { kind: 'TURN_END' }),
      event('tool', { source: 'active-tool', entryId: null }),
    ])
    act(() => result.current.switchMode('debug'))
    expect(result.current.selectedEventId).toBe('end')
  })

  it('waits through an empty or synthetic-only timeline for asynchronous history', () => {
    const { result, rerender } = setup()
    act(() => result.current.switchMode('debug'))
    expect(result.current.selectedEventId).toBeNull()
    rerender({ key: 'pane:a', events: [event('live', { source: 'active-model', entryId: null })] })
    expect(result.current.selectedEventId).toBeNull()
    rerender({ key: 'pane:a', events: [event('model', { historicalPreviewEligible: true })] })
    expect(result.current.selectedEventId).toBe('model')
  })

  it('preserves a valid selection and both saved scroll positions on re-entry', () => {
    const { result, transcript } = setup([event('a'), event('b')])
    transcript.current.scrollTop = 31
    act(() => result.current.switchMode('conversation')) // 同模式无副作用
    act(() => result.current.switchMode('debug'))
    expect(result.current.initialConversationScrollTop).toBeNull()
    act(() => result.current.selectEvent('a'))
    const debugBody = document.createElement('div')
    debugBody.scrollTop = 177
    result.current.eventsBodyRef.current = debugBody
    act(() => result.current.switchMode('conversation'))
    expect(result.current.selectedEventId).toBe('a')
    expect(result.current.initialConversationScrollTop).toBe(31)
    act(() => result.current.switchMode('debug'))
    expect(result.current.initialEventsScrollTop).toBe(177)
    expect(result.current.selectedEventId).toBe('a')
  })

  it('does not reselect after explicit detail close, refresh, or a second entry', () => {
    const { result, rerender } = setup([event('a')])
    act(() => result.current.switchMode('debug'))
    act(() => result.current.selectEvent(null))
    rerender({ key: 'pane:a', events: [event('a'), event('b', { historicalPreviewEligible: true })] })
    expect(result.current.selectedEventId).toBeNull()
    act(() => result.current.switchMode('conversation'))
    act(() => result.current.switchMode('debug'))
    expect(result.current.selectedEventId).toBeNull()
  })

  it('allows explicit close to cancel pending initial selection before history arrives', () => {
    const { result, rerender } = setup()
    act(() => result.current.switchMode('debug'))
    act(() => result.current.selectEvent(null))
    rerender({ key: 'pane:a', events: [event('a')] })
    expect(result.current.selectedEventId).toBeNull()
  })

  it('clears an invalid selection without hijacking it with a replacement', () => {
    const { result, rerender } = setup([event('a')])
    act(() => result.current.switchMode('debug'))
    rerender({ key: 'pane:a', events: [event('b')] })
    expect(result.current.selectedEventId).toBeNull()
  })

  it('resets mode, selection, initial selection ownership and scroll when viewKey changes', () => {
    const { result, rerender } = setup([event('a')])
    act(() => result.current.switchMode('debug'))
    act(() => result.current.selectEvent(null))
    act(() => result.current.switchMode('conversation'))
    rerender({ key: 'pane:b', events: [event('b')] })
    expect(result.current.mode).toBe('conversation')
    expect(result.current.selectedEventId).toBeNull()
    expect(result.current.initialEventsScrollTop).toBeNull()
    expect(result.current.initialConversationScrollTop).toBeNull()
    act(() => result.current.switchMode('debug'))
    expect(result.current.selectedEventId).toBe('b')
  })
})
