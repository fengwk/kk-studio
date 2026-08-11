import { useId } from 'react'
import { CanvasAddMenu } from '@/features/canvas/agent/CanvasAddMenu'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { PlusIcon } from '@/features/canvas/icons'
import {
  MAX_CANVAS_ZOOM,
  MIN_CANVAS_ZOOM,
} from '@/features/canvas/viewport-storage'
import { useI18n } from '@/shared/i18n'

/** 左侧功能轨向下扩展；缩放控制独立留在左下角。 */
export function CanvasToolRail() {
  const {
    state,
    dockAddRef,
    toggleAddMenu,
    fitViewRef,
    zoomRef,
  } = useCanvasRuntime()
  const { t } = useI18n()
  const menuId = useId()

  return (
    <>
      <div className="canvas-tool-rail">
        <div className="tool-rail-add">
          <button
            className="dock-add"
            type="button"
            ref={dockAddRef}
            aria-label={t('canvas.add.ariaLabel')}
            aria-expanded={state.addMenuOpen}
            aria-controls={menuId}
            onClick={toggleAddMenu}
          >
            <PlusIcon />
          </button>
          <CanvasAddMenu menuId={menuId} />
        </div>
      </div>
      <div className="canvas-zoom-controls" role="group" aria-label={t('canvas.stage.zoomControls')}>
        <button type="button" aria-label={t('canvas.stage.zoomOut')} onClick={() => zoomRef.current?.(Math.max(MIN_CANVAS_ZOOM, state.viewport.zoom / 1.15))}>−</button>
        <button type="button" aria-label={t('canvas.stage.fitAll')} onClick={() => fitViewRef.current?.()}>⊙</button>
        <button
          type="button"
          aria-label={t('canvas.stage.resetZoom')}
          onClick={() => zoomRef.current?.(1)}
        >
          {`${Math.round(state.viewport.zoom * 100)}%`}
        </button>
        <button type="button" aria-label={t('canvas.stage.zoomIn')} onClick={() => zoomRef.current?.(Math.min(MAX_CANVAS_ZOOM, state.viewport.zoom * 1.15))}>＋</button>
      </div>
    </>
  )
}
