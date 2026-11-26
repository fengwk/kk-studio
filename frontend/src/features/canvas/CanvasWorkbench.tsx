import { CanvasEditor } from './CanvasEditor'
import { CanvasOverlays } from './CanvasOverlays'
import { CanvasRuntimeProvider } from './CanvasRuntimeContext'
import { canvasViewKey } from './canvas-view-key'
import './canvas.css'

export function CanvasWorkbench({ canvasId, viewId }: { canvasId: string; viewId?: string }) {
  return <CanvasRuntimeProvider key={canvasViewKey(canvasId, viewId)} canvasId={canvasId} viewId={viewId}>
    <div className="canvas-feature canvas-workbench">
      <CanvasEditor />
      <CanvasOverlays />
    </div>
  </CanvasRuntimeProvider>
}
