import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { ChevronDown, ChevronUp } from 'lucide-react'
import {
  flattenThinkingText,
  normalizeThinkingText,
  projectThinkingLine,
  type ThinkingLineProjection,
} from '@/features/ai/runtime/thread-panel/messages/thinking-text'
import { useI18n } from '@/shared/i18n'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import './ThinkingBlock.css'

/** 收起态前文省略标记；仅当前文确实放不下时出现。 */
const ELLIPSIS = '…'

const FULL_LINE: ThinkingLineProjection = { text: '', truncated: false }

/**
 * 思考块：一个框，没有 Header、标题或内部分隔线，按钮固定在第一行末尾。
 * 默认收起为按可用宽度投影的单行尾部；展开后由 MarkdownRenderer 原样渲染完整
 * 思考 Markdown。展开/收起是用户状态，不随流式刷新或终态重置。
 */
export function ThinkingBlock({ thinking }: { thinking: string }) {
  const { t } = useI18n()
  const normalized = normalizeThinkingText(thinking)
  const flattened = flattenThinkingText(thinking)
  const [expanded, setExpanded] = useState(false)
  const lineRef = useRef<HTMLDivElement>(null)
  const lineWidth = useElementWidth(lineRef, !expanded)
  const [projection, setProjection] = useState<ThinkingLineProjection>(FULL_LINE)

  // 投影依赖真实字体度量与元素宽度，只能在布局完成后计算；未测得宽度时保留整行由 CSS 裁剪。
  useLayoutEffect(() => {
    const element = lineRef.current
    if (expanded || !element || lineWidth <= 0) {
      setProjection(flattened ? { text: flattened, truncated: false } : FULL_LINE)
      return
    }
    setProjection(
      projectThinkingLine(flattened, lineWidth, (text) => measureTextWidth(element, text)),
    )
  }, [expanded, flattened, lineWidth])

  if (!normalized) {
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
        onClick={() => setExpanded((current) => !current)}
      >
        {expanded ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
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
 * 用收起态单行一致的字形测量文本宽度。canvas 是浏览器标准度量方式；
 * 无法取得 2D 上下文时返回 0，即不制造截断，交给 CSS 裁剪。
 */
function measureTextWidth(element: HTMLElement, text: string): number {
  if (measureContext === undefined) {
    measureContext = document.createElement('canvas').getContext('2d')
  }
  if (!measureContext) {
    return 0
  }
  const style = window.getComputedStyle(element)
  measureContext.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
  return measureContext.measureText(text).width
}
