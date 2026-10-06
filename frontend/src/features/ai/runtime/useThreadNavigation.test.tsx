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

  it('restores the first popped descendant trigger when truncating to an ancestor', () => {
    // 回到中间祖先时，被隐藏的第一层（CHILD）才是“点进孙子层”的触发点所在层；
    // 不能把焦点还给被截断的最后一层，更不能还给被隐藏的父层记录。
    const inRoot = document.createElement('a')
    inRoot.href = '#in-root'
    const inChild = document.createElement('a')
    inChild.href = '#in-child'
    const inGrandchild = document.createElement('a')
    inGrandchild.href = '#in-grandchild'
    document.body.append(inRoot, inChild, inGrandchild)

    const { result } = renderNavigation()
    inRoot.focus()
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers[0].returnFocus).toBe(inRoot)
    inChild.focus()
    act(() => result.current.openThread(GRANDCHILD))
    inGrandchild.focus()
    act(() => result.current.openThread('00000000-0000-4000-8000-000000000004'))

    // 三层路径 → 截断回 CHILD：焦点回到进入 GRANDCHILD 时所在的 CHILD 层的触发点。
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers.map((layer) => layer.threadId)).toEqual([CHILD])
    expect(document.activeElement).toBe(inChild)
    // 再回到根：焦点回到进入第一层时的触发点。
    act(() => result.current.openThread(ROOT))
    expect(result.current.layers).toEqual([])
    expect(document.activeElement).toBe(inRoot)
  })

  it('falls back to the pane container when the first popped trigger is gone', () => {
    const { result, fallback } = renderNavigation()
    const trigger = document.createElement('a')
    trigger.href = '#trigger'
    document.body.append(trigger)
    trigger.focus()
    act(() => result.current.openThread(CHILD))
    act(() => result.current.openThread(GRANDCHILD))
    trigger.remove()
    act(() => result.current.goToRoot())
    expect(result.current.layers).toEqual([])
    expect(document.activeElement).toBe(fallback)
  })

  it('does not re-focus when a link points at the layer already on top', () => {
    const trigger = document.createElement('a')
    trigger.href = '#trigger'
    const elsewhere = document.createElement('button')
    document.body.append(trigger, elsewhere)

    const { result } = renderNavigation()
    trigger.focus()
    act(() => result.current.openThread(CHILD))
    elsewhere.focus()
    // 顶层自身的链接不是返回：不截断、不把焦点搬回进入该层时的位置。
    act(() => result.current.openThread(CHILD))
    expect(result.current.layers.map((layer) => layer.threadId)).toEqual([CHILD])
    expect(document.activeElement).toBe(elsewhere)
  })

  it('never revives an old path after root R1 -> R2 -> R1', () => {
    // ABA：旧路径必须在根变化时真正从 state 丢弃，回到同一个根也不能复活。
    const fallback = document.createElement('button')
    document.body.append(fallback)
    const R2 = '00000000-0000-4000-8000-00000000000f'
    const { result, rerender } = renderHook(
      ({ root }: { root: string }) => useThreadNavigation({
        rootThreadId: root,
        enabled: true,
        fallbackFocusRef: { current: fallback },
      }),
      { initialProps: { root: ROOT }, wrapper: StrictMode },
    )
    act(() => result.current.openThread(CHILD))
    act(() => result.current.openThread(GRANDCHILD))
    expect(result.current.layers).toHaveLength(2)

    rerender({ root: R2 })
    expect(result.current.layers).toEqual([])
    act(() => result.current.openThread('00000000-0000-4000-8000-000000000005'))
    expect(result.current.layers).toHaveLength(1)

    rerender({ root: ROOT })
    expect(result.current.layers).toEqual([])
    expect(result.current.activeThreadId).toBeNull()
  })

  it('discards a pending focus restore when the root binding changes before commit', () => {
    const fallback = document.createElement('button')
    document.body.append(fallback)
    const R2 = '00000000-0000-4000-8000-00000000000f'
    const { result, rerender } = renderHook(
      ({ root }: { root: string }) => useThreadNavigation({
        rootThreadId: root,
        enabled: true,
        fallbackFocusRef: { current: fallback },
      }),
      { initialProps: { root: ROOT }, wrapper: StrictMode },
    )
    const trigger = document.createElement('a')
    trigger.href = '#trigger'
    document.body.append(trigger)
    trigger.focus()
    act(() => result.current.openThread(CHILD))
    const elsewhere = document.createElement('button')
    document.body.append(elsewhere)
    elsewhere.focus()

    // 同一次批处理里既返回又换根：根变化先作废待恢复焦点，不再把焦点还给旧根的触发点。
    act(() => {
      result.current.goBack()
      rerender({ root: R2 })
    })
    expect(result.current.layers).toEqual([])
    expect(document.activeElement).toBe(elsewhere)
  })
})
