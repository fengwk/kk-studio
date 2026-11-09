import { ChevronDown, ChevronRight, ChevronUp } from 'lucide-react'
import { useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { announceTranscriptReading } from '@/features/thread/runtime/transcript-reading'
import { useI18n } from '@/shared/i18n'
import { HEADER_COLLAPSED_LINES } from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import type { ThreadDisclosureProps } from '@/features/thread/runtime/thread-panel/messages/thread-disclosure.types'
import './thread-disclosure.css'

/**
 * 只读消息的统一布局外壳：Header（名称/参数/操作/chevron）、preview、body 与
 * 长卡片底部的「收起」。它只负责布局和展开交互；语义、配额与内容截取由调用方决定。
 *
 * - chevron 只在收起/展开视图不同时出现：收起时由 hasDetails 或 header 的 CSS 截断
 *   决定，展开时始终显示；
 * - 展开视图不做内部滚动；卡片高于可视区时底部出现「收起」，点击后把卡片顶部
 *   滚到可视区顶部；
 * - 展开/收起属于阅读意图，通知外层 transcript 暂停自动贴底。
 */
export function ThreadDisclosure({
  header,
  actions,
  tone,
  expanded,
  onExpandedChange,
  hasDetails,
  preview,
  body,
  label,
  className,
  ...rest
}: ThreadDisclosureProps) {
  const { t } = useI18n()
  const sectionRef = useRef<HTMLElement>(null)
  const headerRef = useRef<HTMLDivElement>(null)
  const [headerClipped, setHeaderClipped] = useState(false)
  const [overflowsViewport, setOverflowsViewport] = useState(false)

  // header 收起时只有两行；CSS 截断无法用内容判断，必须测量是否真的溢出。
  // 内容变化可能只增加被 clamp 掉的第三行，元素外框尺寸不变、ResizeObserver 不触发：
  // 因此 header 变化后同步重测，并用 MutationObserver 覆盖同一节点内部的文本更新。
  useLayoutEffect(() => {
    const element = headerRef.current
    if (element == null || expanded) {
      setHeaderClipped(false)
      return
    }
    setHeaderClipped(element.scrollHeight > element.clientHeight + 1)
  }, [expanded, header])

  useLayoutEffect(() => {
    if (expanded) {
      return
    }
    const element = headerRef.current
    if (element == null) {
      return
    }
    const measure = () => {
      setHeaderClipped(element.scrollHeight > element.clientHeight + 1)
    }
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure)
      return () => window.removeEventListener('resize', measure)
    }
    const resizeObserver = new ResizeObserver(measure)
    resizeObserver.observe(element)
    const mutationObserver = typeof MutationObserver === 'undefined'
      ? null
      : new MutationObserver(measure)
    mutationObserver?.observe(element, { childList: true, characterData: true, subtree: true })
    return () => {
      resizeObserver.disconnect()
      mutationObserver?.disconnect()
    }
  }, [expanded])

  // 展开后卡片高于 transcript 可视区时才提供底部收起入口。
  useLayoutEffect(() => {
    if (!expanded) {
      setOverflowsViewport(false)
      return
    }
    const element = sectionRef.current
    if (element == null) {
      return
    }
    const scroller = findScrollContainer(element)
    const measure = () => {
      const viewportHeight = scroller?.clientHeight ?? window.innerHeight
      setOverflowsViewport(element.getBoundingClientRect().height > viewportHeight + 1)
    }
    measure()
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure)
      return () => window.removeEventListener('resize', measure)
    }
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    if (scroller != null) {
      observer.observe(scroller)
    }
    return () => observer.disconnect()
  }, [expanded])

  const toggle = () => {
    const element = sectionRef.current
    if (element != null) {
      announceTranscriptReading(element, 'disclosure-toggle')
    }
    onExpandedChange(!expanded)
  }

  const collapseFromBottom = () => {
    const element = sectionRef.current
    if (element == null) {
      onExpandedChange(false)
      return
    }
    const scroller = findScrollContainer(element)
    announceTranscriptReading(element, 'disclosure-collapse')
    onExpandedChange(false)
    // 收起只改变卡片自身高度；下一帧把卡片顶边对齐到滚动容器顶部。
    requestAnimationFrame(() => {
      if (!element.isConnected) {
        return
      }
      if (scroller == null) {
        element.scrollIntoView({ block: 'start' })
      } else {
        const offset = element.getBoundingClientRect().top - scroller.getBoundingClientRect().top
        scroller.scrollTop += offset
      }
      // 底部按钮已随收起卸载，把键盘焦点交还顶部 chevron，而不是落到 document.body。
      element.querySelector<HTMLButtonElement>('.thread-disclosure-toggle')?.focus({ preventScroll: true })
    })
  }

  const canToggle = expanded || hasDetails || headerClipped
  const toggleLabel = expanded
    ? t('ai.runtime.disclosure.collapseDetails')
    : t('ai.runtime.disclosure.expandDetails')

  return (
    <section
      {...rest}
      ref={sectionRef}
      className={['thread-block', 'thread-disclosure', `is-${tone}`, className].filter(Boolean).join(' ')}
      aria-label={label}
      title={label}
    >
      <div className="thread-disclosure-header">
        <div
          ref={headerRef}
          className={`thread-disclosure-header-text${expanded ? '' : ' is-clamped'}`}
          style={expanded
            ? undefined
            : { WebkitLineClamp: HEADER_COLLAPSED_LINES } as CSSProperties}
        >
          {header}
        </div>
        {actions != null ? <div className="thread-disclosure-actions">{actions}</div> : null}
        {canToggle ? (
          <button
            type="button"
            className="thread-disclosure-toggle"
            aria-expanded={expanded}
            aria-label={toggleLabel}
            title={toggleLabel}
            onClick={toggle}
          >
            {expanded
              ? <ChevronDown aria-hidden="true" />
              : <ChevronRight aria-hidden="true" />}
          </button>
        ) : null}
      </div>
      {preview != null ? <div className="thread-disclosure-preview">{preview}</div> : null}
      {body != null ? <div className="thread-disclosure-body">{body}</div> : null}
      {expanded && overflowsViewport ? (
        <button
          type="button"
          className="thread-disclosure-collapse"
          onClick={collapseFromBottom}
        >
          <ChevronUp aria-hidden="true" />
          <span>{t('ai.runtime.disclosure.collapse')}</span>
        </button>
      ) : null}
    </section>
  )
}

/** 最近的可滚动祖先；没有时由 window 作为可视区。 */
function findScrollContainer(element: HTMLElement): HTMLElement | null {
  let current = element.parentElement
  while (current != null) {
    const overflowY = getComputedStyle(current).overflowY
    if (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') {
      return current
    }
    current = current.parentElement
  }
  return null
}
