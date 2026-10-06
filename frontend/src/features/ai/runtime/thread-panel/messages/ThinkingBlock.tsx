import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import {
  flattenThinkingText,
  normalizeThinkingText,
  projectThinkingLine,
  type ThinkingLineProjection,
} from '@/features/ai/runtime/thread-panel/messages/thinking-text'
import { announceTranscriptReading } from '@/features/ai/runtime/transcript-reading'
import { useI18n } from '@/shared/i18n'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import './ThinkingBlock.css'

/** 收起态前文省略标记；仅当前文确实放不下时出现。 */
const ELLIPSIS = '…'

const FULL_LINE: ThinkingLineProjection = { text: '', truncated: false }

/**
 * 思考块：一个框，没有 Header、标题或内部分隔线，按钮固定在第一行末尾。
 * 默认收起为按可用宽度投影的单行尾部（ChevronRight 展开）；展开后同一框由
 * MarkdownRenderer 原样渲染完整思考 Markdown（ChevronDown 收起）。展开/收起是
 * 用户状态，不随流式刷新或终态重置。
 */
export function ThinkingBlock({ thinking }: { thinking: string }) {
  const { t } = useI18n()
  const normalized = normalizeThinkingText(thinking)
  const hasContent = normalized.length > 0
  const flattened = flattenThinkingText(thinking)
  const [expanded, setExpanded] = useState(false)
  const lineRef = useRef<HTMLDivElement>(null)
  // 收起态单行节点只在有内容时挂载；节点出现（空 -> 流式非空）必须重新注册测量。
  const lineVisible = hasContent && !expanded
  const lineWidth = useElementWidth(lineRef, lineVisible)
  const [projection, setProjection] = useState<ThinkingLineProjection>(FULL_LINE)

  // 投影依赖真实字体度量与元素宽度，只能在布局完成后计算；未测得宽度时保留整行由 CSS 裁剪。
  useLayoutEffect(() => {
    const element = lineRef.current
    if (!lineVisible || !element || lineWidth <= 0) {
      setProjection(flattened ? { text: flattened, truncated: false } : FULL_LINE)
      return
    }
    setProjection(projectThinkingLine(flattened, lineWidth, createTextMeasurer(element)))
  }, [flattened, lineVisible, lineWidth])

  if (!hasContent) {
    return null
  }

  return (
    <section className="thread-block thread-block-thinking">
      <div className="thread-thinking-content">
        {expanded ? (
          <div className="thread-block-body thread-thinking-text">
            <MarkdownRenderer content={normalized} tone="muted" />
          </div>
        ) : (
          <div
            className="thread-block-body thread-thinking-text thread-thinking-line"
            ref={lineRef}
          >
            {projection.truncated ? (
              <span className="thread-thinking-ellipsis" aria-hidden="true">{ELLIPSIS}</span>
            ) : null}
            <span className="thread-thinking-tail">{projection.text}</span>
          </div>
        )}
      </div>
      <button
        type="button"
        className="thread-thinking-toggle"
        aria-expanded={expanded}
        aria-label={t(expanded ? 'ai.runtime.thinking.collapse' : 'ai.runtime.thinking.expand')}
        onClick={(event) => {
          announceTranscriptReading(event.currentTarget, 'thinking-toggle')
          setExpanded((current) => !current)
        }}
      >
        {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
      </button>
    </section>
  )
}

/**
 * 收起态单行的可用内容宽度。ResizeObserver 覆盖 pane 缩放、分屏与字体变化，
 * 宽度变化时重新投影尾部，不依赖固定字符数估算容器宽度。
 */
function useElementWidth(ref: RefObject<HTMLElement | null>, enabled: boolean): number {
  const [width, setWidth] = useState(0)

  // 布局阶段测量，保证首帧就按真实宽度投影，不会先整行裁切再跳变。
  useLayoutEffect(() => {
    if (!enabled) {
      return
    }
    const element = ref.current
    if (!element) {
      return
    }
    const update = () => setWidth(element.clientWidth)
    update()
    const observer = new ResizeObserver(update)
    observer.observe(element)
    return () => observer.disconnect()
  }, [enabled, ref])

  return enabled ? width : 0
}

let measureContext: CanvasRenderingContext2D | null | undefined

/**
 * 为一次投影创建文本度量：字体只从元素读取一次，二分期间复用同一度量。
 * 无法取得 2D 上下文时返回 0，即不制造截断，交给 CSS 裁剪。
 */
function createTextMeasurer(element: HTMLElement): (text: string) => number {
  const context = getMeasureContext()
  if (!context) {
    return () => 0
  }
  const style = window.getComputedStyle(element)
  context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
  return (text) => context.measureText(text).width
}

function getMeasureContext(): CanvasRenderingContext2D | null {
  if (measureContext === undefined) {
    measureContext = document.createElement('canvas').getContext('2d')
  }
  return measureContext
}
