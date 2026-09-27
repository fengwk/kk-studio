import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'

export function CanvasOverlays() {
  const {
    state,
    dismissDraft,
    retryDraft,
    saveDraftAsNewNode,
    setToast,
  } = useCanvasRuntime()
  const uploads = Object.entries(state.uploadProgress)

  const conflictedEntries = Object.entries(state.drafts).filter(
    ([, draft]) => Boolean(draft.conflict),
  )

  return (
    <>
      <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
        {state.toast}
      </div>
      {state.storageError ? (
        <div className="canvas-storage-error-banner" role="alert">
          <span>本地草稿持久化落盘失败：{state.storageError}</span>
        </div>
      ) : null}
      {conflictedEntries.map(([nodeId, draft]) => {
        const isRemoteDeleted = draft.conflict?.type === 'remote_deleted' || draft.conflict?.kind === 'TARGET_MISSING'
        const message = draft.conflict?.message
          || (isRemoteDeleted ? '节点已被远端删除，本地保留未保存草稿' : '节点内容与远端存在冲突')
        return (
          <div key={nodeId} className="canvas-conflict-banner" role="alert" data-node-id={nodeId}>
            <span className="canvas-conflict-message">{message}</span>
            <div className="canvas-conflict-actions">
              {isRemoteDeleted ? (
                <button
                  type="button"
                  className="canvas-conflict-action-restore"
                  onClick={() => void saveDraftAsNewNode(nodeId)}
                >
                  另存为新节点
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    className="canvas-conflict-action-retry"
                    onClick={() => void retryDraft(nodeId)}
                  >
                    以远端基线重试
                  </button>
                  <button
                    type="button"
                    className="canvas-conflict-action-restore"
                    onClick={() => void saveDraftAsNewNode(nodeId)}
                  >
                    另存为新节点
                  </button>
                </>
              )}
              <button
                type="button"
                className="canvas-conflict-action-dismiss"
                onClick={() => dismissDraft(nodeId)}
              >
                放弃草稿
              </button>
            </div>
          </div>
        )
      })}
      {state.conflictMessage && conflictedEntries.length === 0 ? (
        <div className="canvas-conflict-banner" role="alert">
          <span className="canvas-conflict-message">{state.conflictMessage}</span>
          <div className="canvas-conflict-actions">
            <button
              type="button"
              className="canvas-conflict-action-dismiss"
              onClick={() => setToast('')}
            >
              关闭
            </button>
          </div>
        </div>
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
