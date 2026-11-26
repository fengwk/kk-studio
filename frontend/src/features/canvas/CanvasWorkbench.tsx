import { CanvasEditor } from './CanvasEditor'
import { CanvasOverlays } from './CanvasOverlays'
import { CanvasRuntimeProvider } from './CanvasRuntimeContext'
import { canvasViewKey } from './canvas-view-key'
import './canvas.css'

export function CanvasWorkbench({ canvasId, viewSlot }: { canvasId: string; viewSlot: number }) {
  return <CanvasRuntimeProvider key={canvasViewKey(canvasId, viewSlot)} canvasId={canvasId} viewSlot={viewSlot}>
    <div className="canvas-feature canvas-workbench">
      <CanvasEditor />
      <CanvasOverlays />
    </div>
  </CanvasRuntimeProvider>
}
