import { createRef } from 'react'
import { act, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadConversationView } from '@/features/thread/runtime/thread-panel/ThreadConversationView'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/thread-timeline/transcript-reading'
import type { TextDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

/**
 * Conversation 视图的自动贴底生命周期（挂载即生效，卸载即消失）。
 *
 * 约定：跟随只由几何驱动（内容/视口的实际尺寸变化），不看消息数、事件数或流式
 * revision；翻转跟随只认真实用户滚动（滚轮 / 滚动键 / 触控 / 滚动条拖拽）与 32px
 * 阈值，程序定位与布局钳制都不翻转；不跟随时绝不写 scrollTop，因此顶部补历史时
 * 保留原阅读锚点。Event 视图的挂载定位由 ThreadEventView.test 覆盖，互斥切换的
 * 集成行为由 ChatWorkspacePane.commands.test 覆盖。
 */

function assistantMessage(id: string, text: string): TextDialogueMessage {
  return {
    id,
    role: 'assistant',
    subjectEntryId: id,
    text,
    createdAt: 1785232800000,
    status: 'done',
  }
}

function messages(count: number): TextDialogueMessage[] {
  return Array.from({ length: count }, (_, index) =>
    assistantMessage(`m${index + 1}`, `reply ${index + 1}`),
  )
}

function dialogue() {
  return document.querySelector<HTMLElement>('.thread-dialogue')!
}

function transcriptContent() {
  return document.querySelector<HTMLElement>('.thread-blocks')!
}

/** 等一帧：贴底调度在布局完成的下一帧执行，必须真正跑到才断言。 */
async function flushLayoutFrame() {
  await act(async () => {
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => resolve())
    })
  })
}

/**
 * 可控 ResizeObserver：jsdom 不布局，几何变化必须由用例显式触发。
 * `fireResize(target)` 只向观察了该目标的 observer 派发一次尺寸变化。
 */
const observers = new Set<ControlledResizeObserver>()

class ControlledResizeObserver implements ResizeObserver {
  readonly targets = new Set<Element>()
  readonly callback: ResizeObserverCallback

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
    observers.add(this)
  }

  observe(target: Element) {
    this.targets.add(target)
  }

  unobserve(target: Element) {
    this.targets.delete(target)
  }

  disconnect() {
    this.targets.clear()
    observers.delete(this)
  }
}

function fireResize(target: Element) {
  for (const observer of [...observers]) {
    const entries = [...observer.targets]
      .filter((element) => element === target)
      .map((element) => ({ target: element }) as ResizeObserverEntry)
    if (entries.length > 0) {
      observer.callback(entries, observer)
    }
  }
}

function renderView(bodyRef: React.RefObject<HTMLDivElement | null>, props: {
  messageCount?: number
  loading?: boolean
  initialScrollTop?: number | null
  resetKey?: string | null
} = {}) {
  const view = render(
    <ThreadConversationView
      messages={messages(props.messageCount ?? 2)}
      loading={props.loading ?? false}
      error={null}
      bodyRef={bodyRef}
      initialScrollTop={props.initialScrollTop ?? null}
      resetKey={props.resetKey ?? null}
    />,
  )
  return {
    rerender(props: {
      messageCount?: number
      loading?: boolean
      initialScrollTop?: number | null
      resetKey?: string | null
    } = {}) {
      view.rerender(
        <ThreadConversationView
          messages={messages(props.messageCount ?? 2)}
          loading={props.loading ?? false}
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
  beforeEach(() => {
    observers.clear()
    vi.stubGlobal('ResizeObserver', ControlledResizeObserver)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sticks to the bottom on first entry and follows content growth', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      expect(dialogue().scrollTop).toBe(600)

      // 内容变高（正文流式增长、卡片定稿）：用变化前的跟随状态直接滚到新底部。
      scrollHeightSpy.mockReturnValue(1160)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(1160)

      scrollHeightSpy.mockReturnValue(1400)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(1400)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('follows direct-child changes of the scroll body (loading state, new rows)', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { loading: true })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)

      // 直接子节点增删同样是几何信号：加载态消失既换观察目标也贴底。
      scrollHeightSpy.mockReturnValue(900)
      view.rerender({ loading: false })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(900)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('follows viewport geometry as well as content geometry', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)

      // 分屏 / 窗口缩放改变视口：跟随状态不变，继续贴底。
      clientHeightSpy.mockReturnValue(300)
      fireResize(dialogue())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('moves only on geometry, never on render counts', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef)
      await flushLayoutFrame()

      // 用户回看：离开阈值即停止跟随。
      fireEvent.wheel(dialogue(), { deltaY: -100 })
      dialogue().scrollTop = 300
      fireEvent.scroll(dialogue())

      // 只改渲染数据、没有几何变化：位置必须原地不动。
      view.rerender({ messageCount: 8 })
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)

      // 真正的几何变化同样不把回看中的用户拉回底部。
      scrollHeightSpy.mockReturnValue(900)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('restores a saved position and derives following from the 32px threshold', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()

      // 恢复位置 400：距底部 0px（<=32 阈值）→ 继续跟随。
      const atBottom = renderView(bodyRef, { initialScrollTop: 400 })
      expect(dialogue().scrollTop).toBe(400)
      scrollHeightSpy.mockReturnValue(900)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(900)
      atBottom.unmount()

      // 恢复位置 370：距底部 30px，仍在阈值内 → 继续跟随。
      scrollHeightSpy.mockReturnValue(600)
      const nearBottom = renderView(bodyRef, { initialScrollTop: 370 })
      expect(dialogue().scrollTop).toBe(370)
      scrollHeightSpy.mockReturnValue(900)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(900)
      nearBottom.unmount()

      // 恢复位置 100：距底部 300px（>32 阈值）→ 不打断回看。
      scrollHeightSpy.mockReturnValue(600)
      const history = renderView(bodyRef, { initialScrollTop: 100 })
      expect(dialogue().scrollTop).toBe(100)
      scrollHeightSpy.mockReturnValue(900)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(100)
      history.unmount()
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('leaves following past the threshold and resumes only for genuine gestures', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      await flushLayoutFrame()

      // 阈值内上滑（400 → 390，距底 10px）不翻转跟随：几何增长仍然贴底。
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      fireEvent.wheel(dialogue(), { deltaY: -10 })
      dialogue().scrollTop = 390
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)

      // 离开阈值（300 → 距底 100px）：停止跟随。
      fireEvent.wheel(dialogue(), { deltaY: -100 })
      dialogue().scrollTop = 300
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)

      // 程序定位回底部没有用户意图：不得恢复跟随。
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(400)

      // 用户继续上滑离开阈值，再用触控滚回阈值内 → 恢复跟随。
      dialogue().scrollTop = 250
      fireEvent.scroll(dialogue())
      fireEvent.touchMove(dialogue(), { touches: [] })
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('reopens following for wheel, keyboard and scrollbar-drag intent alike', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      await flushLayoutFrame()

      // 滚轮意图回到底部。
      fireEvent.wheel(dialogue(), { deltaY: -200 })
      dialogue().scrollTop = 200
      fireEvent.scroll(dialogue())
      fireEvent.wheel(dialogue(), { deltaY: 200 })
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)

      // 键盘滚动意图回到底部。
      fireEvent.wheel(dialogue(), { deltaY: -300 })
      dialogue().scrollTop = 100
      fireEvent.scroll(dialogue())
      fireEvent.keyDown(dialogue(), { key: 'End' })
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)

      // 抓住右侧滚动条拖动回到底部。
      fireEvent.wheel(dialogue(), { deltaY: -350 })
      dialogue().scrollTop = 50
      fireEvent.scroll(dialogue())
      fireEvent.pointerDown(dialogue(), { clientX: 1190, clientY: 10 })
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('cancels a queued bottom scroll when the user scrolls up in the same frame', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      await flushLayoutFrame()
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())

      // 几何变化调度了贴底帧，同一帧内用户上滑（仍在阈值内）→ 贴底必须被作废。
      fireResize(transcriptContent())
      fireEvent.wheel(dialogue(), { deltaY: -120 })
      dialogue().scrollTop = 390
      fireEvent.scroll(dialogue())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(390)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('leaves scroll anchoring to the browser when not following', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      await flushLayoutFrame()

      fireEvent.wheel(dialogue(), { deltaY: -100 })
      dialogue().scrollTop = 300
      fireEvent.scroll(dialogue())

      // 回看中，hook 不写 scrollTop；真实补历史的锚点由浏览器测试验证。
      expect(dialogue()).toHaveAttribute('data-following', 'false')
      scrollHeightSpy.mockReturnValue(1200)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(300)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('ignores nested reading intents: expanding detail never flips following', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      await flushLayoutFrame()

      // 内层只读区域上报阅读意图不再暂停跟随：展开详情、选择文本都不翻转跟随。
      dialogue().dispatchEvent(new CustomEvent(TRANSCRIPT_READING_INTENT_EVENT, {
        bubbles: true,
        detail: { source: 'disclosure-toggle' },
      }))
      scrollHeightSpy.mockReturnValue(660)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(660)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('never reopens following on a layout clamp to the bottom', async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(900)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      renderView(bodyRef)
      await flushLayoutFrame()

      // 用户回看 → 停止跟随。
      fireEvent.wheel(dialogue(), { deltaY: -420 })
      dialogue().scrollTop = 480
      fireEvent.scroll(dialogue())
      scrollHeightSpy.mockReturnValue(1200)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(480)

      // 内容收起：布局变矮，浏览器把 scrollTop 钳制到新的底部（600 - 200 = 400）。
      scrollHeightSpy.mockReturnValue(600)
      dialogue().scrollTop = 400
      fireEvent.scroll(dialogue())

      // 即使此刻正好在底部，也不是用户回底：后续增长不得贴底。
      scrollHeightSpy.mockReturnValue(900)
      fireResize(transcriptContent())
      await flushLayoutFrame()
      expect(dialogue().scrollTop).toBe(400)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('remounts with a fresh following lifecycle (previous pause never leaks)', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const first = renderView(bodyRef)
      expect(dialogue().scrollTop).toBe(600)
      fireEvent.wheel(dialogue(), { deltaY: -300 })
      dialogue().scrollTop = 100
      fireEvent.scroll(dialogue())
      first.unmount()

      // 重新挂载（首次进入语义）：必须重新贴底，而不是沿用上次的暂停状态。
      renderView(bodyRef, { messageCount: 3 })
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('resets following and re-sticks on resetKey change (thread rebind first entry)', () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const bodyRef = createRef<HTMLDivElement>()
      const view = renderView(bodyRef, { resetKey: 't1' })
      expect(dialogue().scrollTop).toBe(600)
      fireEvent.wheel(dialogue(), { deltaY: -300 })
      dialogue().scrollTop = 100
      fireEvent.scroll(dialogue())

      // Thread 重绑（resetKey 变化）：跟随重置并重新贴底。
      view.rerender({ resetKey: 't2' })
      expect(dialogue().scrollTop).toBe(600)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })
})
