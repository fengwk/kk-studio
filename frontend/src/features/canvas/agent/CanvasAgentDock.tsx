import { ChevronRight } from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
} from 'react'
import { CanvasAgentThread } from '@/features/canvas/agent/CanvasAgentThread'
import {
  agentPanelWidthBounds,
  clampAgentPanelWidth,
  loadAgentPanelWidth,
  saveAgentPanelWidth,
} from '@/features/canvas/agent-panel-width'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { useI18n } from '@/shared/i18n'

/**
 * Canvas Chat 右侧面板。
 * 仅做组合 —— 面板主体（真实 Thread / blank 首次发送）在 CanvasAgentThread；
 * 本组件保留 resize/header/collapse 外壳与桌面/窄屏行为。
 */
export function CanvasAgentDock() {
  const { state, collapseThread } = useCanvasRuntime()
  const { t } = useI18n()
  const [panelWidth, setPanelWidth] = useState(() => loadAgentPanelWidth(window.innerWidth))
  const resizeRef = useRef<{
    pointerId: number
    startX: number
    startWidth: number
  } | null>(null)

  useEffect(() => {
    const handleResize = () => {
      setPanelWidth((current) => clampAgentPanelWidth(current, window.innerWidth))
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  useEffect(() => {
    saveAgentPanelWidth(panelWidth, window.innerWidth)
  }, [panelWidth])

  useEffect(() => () => {
    document.body.classList.remove('canvas-chat-panel-resizing')
  }, [])

  if (!state.threadOpen) {
    return null
  }

  const widthBounds = agentPanelWidthBounds(window.innerWidth)
  const finishResize = () => {
    if (!resizeRef.current) {
      return
    }
    resizeRef.current = null
    document.body.classList.remove('canvas-chat-panel-resizing')
  }

  return (
    <aside
      className="agent-panel"
      id="agentPanel"
      aria-label={t('canvas.agent.panelAria')}
      style={{ '--agent-panel-width': `${panelWidth}px` } as CSSProperties}
    >
      <div
        className="agent-panel-resize-handle"
        role="separator"
        tabIndex={0}
        aria-label={t('canvas.agent.resize')}
        aria-orientation="vertical"
        aria-valuemin={widthBounds.min}
        aria-valuemax={widthBounds.max}
        aria-valuenow={panelWidth}
        onPointerDown={(event) => {
          event.preventDefault()
          event.stopPropagation()
          resizeRef.current = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startWidth: panelWidth,
          }
          event.currentTarget.setPointerCapture?.(event.pointerId)
          document.body.classList.add('canvas-chat-panel-resizing')
        }}
        onPointerMove={(event) => {
          const resize = resizeRef.current
          if (!resize || resize.pointerId !== event.pointerId) {
            return
          }
          event.preventDefault()
          setPanelWidth(clampAgentPanelWidth(
            resize.startWidth + resize.startX - event.clientX,
            window.innerWidth,
          ))
        }}
        onPointerUp={finishResize}
        onPointerCancel={finishResize}
        onLostPointerCapture={finishResize}
        onKeyDown={(event) => {
          let nextWidth: number | null = null
          if (event.key === 'ArrowLeft') {
            nextWidth = panelWidth + 24
          } else if (event.key === 'ArrowRight') {
            nextWidth = panelWidth - 24
          } else if (event.key === 'Home') {
            nextWidth = widthBounds.min
          } else if (event.key === 'End') {
            nextWidth = widthBounds.max
          }
          if (nextWidth === null) {
            return
          }
          event.preventDefault()
          const next = clampAgentPanelWidth(nextWidth, window.innerWidth)
          setPanelWidth(next)
          saveAgentPanelWidth(next, window.innerWidth)
        }}
      />
      <header className="agent-panel-head">
        <strong>{t('canvas.editor.thread')}</strong>
        <button
          className="collapse-thread"
          type="button"
          aria-label={t('canvas.agent.collapse')}
          onClick={collapseThread}
        >
          <ChevronRight aria-hidden="true" />
        </button>
      </header>
      <CanvasAgentThread />
    </aside>
  )
}
