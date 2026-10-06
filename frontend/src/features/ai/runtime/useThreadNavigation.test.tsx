import { StrictMode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useThreadNavigation } from '@/features/ai/runtime/useThreadNavigation'

const ROOT = '00000000-0000-4000-8000-000000000001'
const CHILD = '00000000-0000-4000-8000-000000000002'
const GRANDCHILD = '00000000-0000-4000-8000-000000000003'

function renderNavigation(rootThreadId: string | null = ROOT, enabled = true) {
  const fallback = document.createElement('button')
  document.body.append(fallback)
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <StrictMode>{children}</StrictMode>
  )
  const hook = renderHook(
    () => useThreadNavigation({ rootThreadId, enabled, fallbackFocusRef: { current: fallback } }),
    { wrapper },
  )
  return { ...hook, fallback }
}

describe('useThreadNavigation', () => {
  it('pushes one layer per distinct thread even when the updater is double-invoked in StrictMode', () => {
    // StrictMode 会重复调用状态更新函数：层记录与 returnFocus 栈都必须保持幂等。
    const { result } = renderNavigation()
    act(() => result.current.openThread(CHILD))
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers.map((layer) => layer.threadId)).toEqual([CHILD])
    expect(result.current.activeThreadId).toBe(CHILD)
  })

  it('truncates to an ancestor already on the path instead of stacking it twice', () => {
    const { result } = renderNavigation()
    act(() => result.current.openThread(CHILD))
    act(() => result.current.openThread(GRANDCHILD))
    expect(result.current.activeThreadId).toBe(GRANDCHILD)
    // 子层里的祖先链接回到该层：不是新增一层，而是截断到它。
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers.map((layer) => layer.threadId)).toEqual([CHILD])
  })

  it('returns to the root layer when a link points at the execution root', () => {
    const { result } = renderNavigation()
    act(() => result.current.openThread(CHILD))
    act(() => result.current.openThread(GRANDCHILD))
    act(() => result.current.openThread(ROOT))
    expect(result.current.layers).toEqual([])
    expect(result.current.activeThreadId).toBeNull()
  })

  it('restores the recorded focus of each layer on back, in order', () => {
    const first = document.createElement('a')
    first.href = '#first'
    const second = document.createElement('a')
    second.href = '#second'
    document.body.append(first, second)
    const { result } = renderNavigation()

    first.focus()
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers[0].returnFocus).toBe(first)

    second.focus()
    act(() => result.current.openThread(GRANDCHILD))
    expect(result.current.layers[1].returnFocus).toBe(second)

    act(() => result.current.goBack())
    expect(document.activeElement).toBe(second)
    act(() => result.current.goBack())
    expect(document.activeElement).toBe(first)
    expect(result.current.layers).toEqual([])
  })

  it('falls back to the pane container when the recorded trigger is gone', () => {
    const { result, fallback } = renderNavigation()
    const detached = document.createElement('a')
    detached.href = '#detached'
    document.body.append(detached)
    detached.focus()
    act(() => result.current.openThread(CHILD))
    detached.remove()
    act(() => result.current.goBack())
    expect(document.activeElement).toBe(fallback)
  })

  it('drops the whole path on a render when the root binding changes', () => {
    // 根绑定变化必须在 render 派生为空路径：旧层不允许出现一帧。
    const fallback = document.createElement('button')
    document.body.append(fallback)
    const { result, rerender } = renderHook(
      ({ root }: { root: string }) => useThreadNavigation({
        rootThreadId: root,
        enabled: true,
        fallbackFocusRef: { current: fallback },
      }),
      { initialProps: { root: ROOT }, wrapper: StrictMode },
    )
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers).toHaveLength(1)
    rerender({ root: ROOT })
    expect(result.current.layers).toHaveLength(1)
    rerender({ root: '00000000-0000-4000-8000-00000000000f' })
    expect(result.current.layers).toEqual([])
    expect(result.current.activeThreadId).toBeNull()
  })

  it('ignores navigation while the pane has no confirmed root', () => {
    const { result } = renderNavigation(ROOT, false)
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers).toEqual([])
  })
})
