import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'

export function CanvasOverlays() {
  const { state } = useCanvasRuntime()
  const uploads = Object.entries(state.uploadProgress)

  return (
    <>
      <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
        {state.toast}
      </div>
      {state.conflictMessage ? (
        <div className="canvas-conflict-banner" role="alert">{state.conflictMessage}</div>
      ) : null}
      {uploads.length > 0 ? (
        <div className="canvas-upload-progress" role="status" aria-live="polite">
          {uploads.map(([name, progress]) => (
            <span key={name}>
              {name.split(':')[0]}
              {' '}
              {Math.round(progress * 100)}
              %
            </span>
          ))}
        </div>
      ) : null}
    </>
  )
}
