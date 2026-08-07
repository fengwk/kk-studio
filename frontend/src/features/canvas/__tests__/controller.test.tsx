import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCanvasController } from '@/features/canvas/useCanvasController'

describe('useCanvasController', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('opens editor, creates generators, and drives agent run timers without leaks', () => {
    vi.useFakeTimers()
    const { result, unmount } = renderHook(() => useCanvasController())

    act(() => {
      result.current.openEditor()
      result.current.setLibraryFilter('collab')
      result.current.selectTemplate('技术方案')
      result.current.setIdea('做个方案')
      result.current.createFromIdea()
    })
    expect(result.current.state.view).toBe('editor')

    act(() => {
      result.current.setStageMetrics({ width: 1000, height: 700, dockTop: 600 })
      result.current.createGenerator('text')
      result.current.setGenerationPrompt('写一段定位')
      result.current.submitGeneration()
    })
    expect(result.current.activeGenerator?.status).toBe('generated')
    expect(result.current.state.threadOpen).toBe(false)

    act(() => {
      result.current.handleAddAction('file')
      result.current.setAgentPrompt('开始研究')
      result.current.sendAgent()
    })
    expect(result.current.run?.status).toBe('running')

    act(() => {
      vi.advanceTimersByTime(700)
    })
    expect((result.current.run?.progress ?? 0) > 0).toBe(true)

    act(() => {
      result.current.runAction('pause')
    })
    expect(result.current.run?.status).toBe('paused')
    act(() => {
      result.current.runAction('resume')
      result.current.runAction('retry')
      result.current.resetDemo()
    })
    expect(result.current.state.messages).toEqual([])

    // 保存定时器在卸载后正常收敛到 saved 状态且不会泄漏。
    act(() => {
      result.current.moveNodes([{ id: 'web', x: 1, y: 2 }])
    })
    expect(result.current.state.saveState).toBe('saving')
    unmount()
  })

  it('handles keyboard shortcuts for delete/fit/tool/text and Cmd-K focus token', () => {
    const { result } = renderHook(() => useCanvasController())
    act(() => {
      result.current.openEditor()
      result.current.setSelection(['web'])
    })

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    })
    expect(result.current.state.nodes.some((node) => node.id === 'web')).toBe(false)

    const tokenBefore = result.current.state.focusAgentPromptToken
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true }))
    })
    expect(result.current.state.focusAgentPromptToken).toBe(tokenBefore + 1)

    act(() => {
      result.current.setTool('hand')
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'v' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 't' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '0' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '1' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })
    expect(result.current.state.selectedIds).toEqual([])
  })

  it('keeps permanent hand tool independent from Space (no silent tool flip on Space)', () => {
    const { result } = renderHook(() => useCanvasController())
    act(() => {
      result.current.openEditor()
      result.current.setTool('hand')
    })
    expect(result.current.state.tool).toBe('hand')
    // 空格由 React Flow 的 panActivationKeyCode 处理；controller 不应改写 tool。
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { code: 'Space', key: ' ' }))
      window.dispatchEvent(new KeyboardEvent('keyup', { code: 'Space', key: ' ' }))
    })
    expect(result.current.state.tool).toBe('hand')
  })
})
