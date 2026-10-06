import { createRef } from 'react'
import { act, fireEvent, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadConversationView } from '@/features/ai/runtime/thread-panel/ThreadConversationView'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/ai/runtime/transcript-reading'
import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/**
 * Conversation 视图的自动贴底生命周期（挂载即生效，卸载即消失）：
 * 首次进入贴底；向历史方向滚动时立即脱离，滚回 210px 阈值内时恢复；重新进入
 * 恢复保存位置并按同一阈值决定 stick；Thread 重绑（resetKey 变化）重置 stick
 * 重新贴底。
 *
 * 贴底只认显式信号：流式正文更新（stream revision）与消息数量增长。布局尺寸变化
 * （展开卡片、图片加载、懒渲染）不产生信号，绝不移动外层滚动位置；内层只读区域
 * 回看/交互冒泡阅读意图事件即暂停跟随，只有用户把外层滚回底部才恢复。
 * Event 视图的 stick 独立由 ThreadEventView.test 覆盖，互斥切换的集成行为由
 * ChatWorkspacePane.commands.test 覆盖。
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

/** 末尾仍未完成的 assistant 消息：唯一产生 stream revision 的流式状态。 */
function streamingMessages(count: number, tailText = 'reply streaming'): DialogueMessage[] {
  const list = messages(count)
  const tail = list[list.length - 1]
  if (tail) {
    list[list.length - 1] = { ...tail, text: tailText, status: 'streaming' }
  }
  return list
}

function dialogue() {
  return document.querySelector<HTMLElement>('.thread-dialogue')!
}

/** 等一帧：流式贴底调度在布局完成的下一帧执行，必须真正跑到才断言。 */
async function flushLayoutFrame() {
  await act(async () => {
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => resolve())
    })
  })
}

function renderView(bodyRef: React.RefObject<HTMLDivElement | null>, props: {
  messageCount?: number
  initialScrollTop?: number | null
  resetKey?: string | null
  streaming?: boolean
  streamText?: string
} = {}) {
  const buildMessages = () => props.streaming
    ? streamingMessages(props.messageCount ?? 2, props.streamText)
    : messages(props.messageCount ?? 2)
  const element = (
    <ThreadConversationView
      messages={buildMessages()}
      loading={false}
      error={null}
      bodyRef={bodyRef}
      initialScrollTop={props.initialScrollTop ?? null}
      resetKey={props.resetKey ?? null}
    />
  )
  const view = render(element)
  return {
    rerender(props: { messageCount?: number; initialScrollTop?: number | null; resetKey?: string | null; streaming?: boolean; streamText?: string } = {}) {
      const nextMessages = props.streaming === true
        ? streamingMessages(props.messageCount ?? 2, props.streamText)
        : messages(props.messageCount ?? 2)
      view.rerender(
        <ThreadConversationView
          messages={nextMessages}
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
  it('sticks to the bottom on first entry and keeps sticking as content grows', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2 })
      expect(dialogue().scrollTop).toBe(600)
      // 内容增长：仍贴底（写入调度到布局完成后的下一帧）。
      view.rerender({ messageCount: 4 })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('restores a saved position and derives initial stick state from the 210px threshold', async () => {
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
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
      first.unmount()

      // 再次重新进入：恢复位置 100 距底部 300px（>210 阈值）→ 不打断回看。
      const second = renderView(bodyRef, { messageCount: 2, initialScrollTop: 100 })
      expect(dialogue().scrollTop).toBe(100)
      // 内容增长：超过阈值 → 绝不拉回底部。
      second.rerender({ messageCount: 6, initialScrollTop: 100 })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(100)
      second.unmount()
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('detaches on the first scroll toward history even inside the restick threshold', async () => {
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
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(390)

      // 用户主动向底部滚回阈值内后恢复跟随。
      dialogue().scrollTop = 395
      fireEvent.scroll(dialogue())
      view.rerender({ messageCount: 4 })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('re-sticks on stream revision updates while following', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2, streaming: true })

      // 用户把外层滚回底部 → stick 恢复。
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())

      // 流式文本更新是显式信号：必须跟到新的底部。
      view.rerender({ messageCount: 2, streaming: true, streamText: 'reply streaming + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)

      // 同样的文本再次渲染不产生新信号，也不该移动位置。
      dialogue().scrollTop = 300
      view.rerender({ messageCount: 2, streaming: true, streamText: 'reply streaming + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('never re-sticks on layout-only growth (media load, image decode, expansion)', async () => {
    // 媒体解码、图片落地、卡片展开都只是高度变化：没有 stream revision，也没有消息
    // 数量增长，外层 scrollTop 必须原地不动。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2, streaming: true })
      // 用户回到历史回看。
      dialogue().scrollTop = 300
      fireEvent.scroll(dialogue())
      view.rerender({ messageCount: 2, streaming: true, streamText: 'reply streaming + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)

      // 高度长大（图片解码完成）同样不移动位置。
      scrollHeightSpy.mockReturnValue(1400)
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('does not reopen following after a reading intent even when the layout shrinks', async () => {
    // 子只读区域回看后：布局变矮（浏览器会把 scrollTop 钳制到新的底部附近）、
    // 后续流式文本更新都不得重开 stick，位置只能由用户自己滚回底部恢复。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(900)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 3, streaming: true })
      dialogue().scrollTop = 700
      fireEvent.scroll(dialogue())

      dialogue().dispatchEvent(new CustomEvent(TRANSCRIPT_READING_INTENT_EVENT, {
        bubbles: true,
        detail: { source: 'tool-output' },
      }))

      // 布局变矮：即使位置被钳制到新的底部，也不得重开跟随。
      scrollHeightSpy.mockReturnValue(600)
      dialogue().scrollTop = 400
      view.rerender({ messageCount: 3, streaming: true, streamText: 'reply streaming + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(400)

      // 后续流式文本继续更新：仍在暂停状态，位置不动。
      scrollHeightSpy.mockReturnValue(900)
      view.rerender({ messageCount: 4, streaming: true, streamText: 'reply streaming + more + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(400)

      // 用户自己滚回底部阈值内：恢复跟随。
      dialogue().scrollTop = 700
      fireEvent.scroll(dialogue())
      view.rerender({ messageCount: 5, streaming: true, streamText: 'reply streaming + more + more + end' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(900)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('cancels a pending bottom scroll when a reading intent arrives in the same frame', async () => {
    // 阅读意图必须直接取消待执行的贴底帧，绝不留到下一帧再贴底。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2, streaming: true })
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())

      // 内容增长调度贴底帧，同一帧内到达阅读意图。
      view.rerender({ messageCount: 3, streaming: true, streamText: 'reply streaming + more' })
      dialogue().dispatchEvent(new CustomEvent(TRANSCRIPT_READING_INTENT_EVENT, {
        bubbles: true,
        detail: { source: 'thinking' },
      }))
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(400)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })


  it('pauses following when a nested readonly view announces reading intent', async () => {
    // 工具输出视口/思考正文回看时冒泡阅读意图：即使外层此刻贴底，后续流式增长
    // 也不能把用户正在看的卡片拉走；只有外层自己滚回底部才恢复。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 2, streaming: true })
      expect(dialogue().scrollTop).toBe(600)

      dialogue().dispatchEvent(new CustomEvent(TRANSCRIPT_READING_INTENT_EVENT, {
        bubbles: true,
        detail: { source: 'tool-output' },
      }))

      // 外层贴底但用户正在读内层：流式增长不得移动位置。
      dialogue().scrollTop = 500
      view.rerender({ messageCount: 3, streaming: true, streamText: 'reply streaming + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(500)

      // 用户主动把外层滚回底部阈值内 → 恢复跟随。
      dialogue().scrollTop = 595
      fireEvent.scroll(dialogue())
      view.rerender({ messageCount: 4, streaming: true, streamText: 'reply streaming + more + more' })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('stops following once the stream reaches a terminal state', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { messageCount: 3, streaming: true })
      expect(dialogue().scrollTop).toBe(600)

      // 流式转终态：没有 stream revision，高度变化（展开/媒体）不再贴底。
      view.rerender({ messageCount: 3, streaming: false })
      await flushLayoutFrame()
      dialogue().scrollTop = 200
      fireEvent.scroll(dialogue())
      scrollHeightSpy.mockReturnValue(900)
      view.rerender({ messageCount: 3, streaming: false })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(200)
    } finally {
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
