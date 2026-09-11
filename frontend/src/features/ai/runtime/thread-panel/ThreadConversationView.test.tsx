import { createRef } from 'react'
import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadConversationView } from '@/features/ai/runtime/thread-panel/ThreadConversationView'
import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/**
 * Conversation 视图的自动贴底生命周期（挂载即生效，卸载即消失）：
 * 首次进入贴底；向历史方向滚动时立即脱离，滚回 210px 阈值内时恢复；重新进入
 * 恢复保存位置并按同一阈值决定 stick；Thread 重绑（resetKey 变化）重置 stick
 * 重新贴底。Event 视图的 stick 独立由 ThreadEventView.test 覆盖，互斥切换的
 * 集成行为由 ChatWorkspacePane.commands.test 覆盖。
 */

function assistantMessage(id: string, text: string): DialogueMessage {
  return {
    id,
    role: 'assistant',
    subjectEntryId: id,
    text,
    createdAt: '2026-07-28T10:00:00Z',
    status: 'done',
  }
}

function messages(count: number): DialogueMessage[] {
  return Array.from({ length: count }, (_, index) =>
    assistantMessage(`m${index + 1}`, `reply ${index + 1}`),
  )
}

function dialogue() {
  return document.querySelector<HTMLElement>('.thread-dialogue')!
}

function renderView(bodyRef: React.RefObject<HTMLDivElement | null>, props: {
  messageCount?: number
  initialScrollTop?: number | null
  resetKey?: string | null
} = {}) {
  const element = (
    <ThreadConversationView
      messages={messages(props.messageCount ?? 2)}
      loading={false}
      error={null}
      bodyRef={bodyRef}
      initialScrollTop={props.initialScrollTop ?? null}
      resetKey={props.resetKey ?? null}
    />
  )
  const view = render(element)
  return {
    rerender(props: { messageCount?: number; initialScrollTop?: number | null; resetKey?: string | null } = {}) {
      view.rerender(
        <ThreadConversationView
          messages={messages(props.messageCount ?? 2)}
          loading={false}
          error={null}
          bodyRef={bodyRef}
          initialScrollTop={props.initialScrollTop ?? null}
          resetKey={props.resetKey ?? null}
        />,
      )
    },
    unmount: view.unmount,
  }
}

describe('ThreadConversationView', () => {
  it('sticks to the bottom on first entry and keeps sticking as content grows', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2 })
      expect(dialogue().scrollTop).toBe(600)
      // 内容增长：仍贴底。
      view.rerender({ messageCount: 4 })
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('restores a saved position and derives initial stick state from the 210px threshold', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()

      // 重新进入：恢复位置 400 距底部 0px（<=210 阈值）→ 仍 stick。
      const first = renderView(bodyRef, { messageCount: 2, initialScrollTop: 400 })
      expect(dialogue().scrollTop).toBe(400)
      // 内容增长：阈值内 → 继续贴底。
      first.rerender({ messageCount: 4, initialScrollTop: 400 })
      expect(dialogue().scrollTop).toBe(600)
      first.unmount()

      // 再次重新进入：恢复位置 100 距底部 300px（>210 阈值）→ 不打断回看。
      const second = renderView(bodyRef, { messageCount: 2, initialScrollTop: 100 })
      expect(dialogue().scrollTop).toBe(100)
      // 内容增长：超过阈值 → 绝不拉回底部。
      second.rerender({ messageCount: 6, initialScrollTop: 100 })
      expect(dialogue().scrollTop).toBe(100)
      second.unmount()
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('detaches on the first scroll toward history even inside the restick threshold', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2 })

      // jsdom 不会像浏览器一样把 scrollHeight 赋值钳制到最大 scrollTop，先模拟钳制。
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())

      // 只离开底部 10px 也代表明确的历史回看意图，内容增长不能把手势拉回底部。
      dialogue().scrollTop = 390
      fireEvent.scroll(dialogue())
      view.rerender({ messageCount: 3 })
      expect(dialogue().scrollTop).toBe(390)

      // 用户主动向底部滚回阈值内后恢复跟随。
      dialogue().scrollTop = 395
      fireEvent.scroll(dialogue())
      view.rerender({ messageCount: 4 })
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('does not let a resize callback override a small scroll toward history', () => {
    const originalResizeObserver = globalThis.ResizeObserver
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    let triggerResize: (target: Element) => void = () => {
      throw new Error('ResizeObserver was not created')
    }
    class ControllableResizeObserver {
      constructor(callback: ResizeObserverCallback) {
        triggerResize = (target) => {
          callback(
            [{ target } as ResizeObserverEntry],
            this as unknown as ResizeObserver,
          )
        }
      }

      observe(target: Element) {
        // 浏览器首次 observe 会报告当前尺寸，hook 会把这一轮识别为初始回调。
        triggerResize(target)
      }

      unobserve() {}
      disconnect() {}
    }

    try {
      globalThis.ResizeObserver =
        ControllableResizeObserver as unknown as typeof ResizeObserver
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)

      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      dialogue().scrollTop = 390
      fireEvent.scroll(dialogue())

      // 模拟移动手势期间并发的容器尺寸回调，不能重新拉到底部。
      triggerResize(dialogue())
      expect(dialogue().scrollTop).toBe(390)
    } finally {
      globalThis.ResizeObserver = originalResizeObserver
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('remounts with a fresh stick lifecycle (previous stick=false never leaks)', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const first = renderView(bodyRef)
      expect(dialogue().scrollTop).toBe(600)
      // 用户向历史方向滚动：stick=false。
      dialogue().scrollTop = 100
      fireEvent.scroll(dialogue())
      first.unmount()

      // 重新挂载（首次进入语义）：必须重新贴底，而不是沿用上次的 stick=false。
      renderView(bodyRef, { messageCount: 3 })
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('resets stick and re-sticks on resetKey change (thread rebind first entry)', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { resetKey: 't1' })
      expect(dialogue().scrollTop).toBe(600)
      // 用户向历史方向滚动：stick=false。
      dialogue().scrollTop = 100
      fireEvent.scroll(dialogue())

      // Thread 重绑（resetKey 变化）：stick 重置并重新贴底。
      view.rerender({ resetKey: 't2' })
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })
})
