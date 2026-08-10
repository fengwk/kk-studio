import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'

export function CanvasOverlays() {
  const { state } = useCanvasRuntime()

  return (
    <>
      <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
        {state.toast}
      </div>
      {state.conflictMessage ? (
        <div className="canvas-conflict-banner" role="alert">{state.conflictMessage}</div>
      ) : null}
    </>
  )
}
