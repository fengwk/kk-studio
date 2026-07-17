import { LoaderCircle, Send, Square } from 'lucide-react'
import type { KeyboardEvent } from 'react'

/**
 * Canvas-dock inspired composer: single clean input row + send.
 * Secondary run controls stay compact when a run is active.
 */
export function SessionComposer({
  draft,
  activeRun,
  pending,
  disabled,
  controlsPending,
  onDraftChange,
  onSubmit,
  onSteer,
  onFollowUp,
  onAbort,
}: {
  draft: string
  activeRun: boolean
  pending: boolean
  disabled: boolean
  controlsPending: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onSteer: () => void
  onFollowUp: () => void
  onAbort: () => void
}) {
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      onSubmit()
    }
  }

  return (
    <div className="session-composer">
      {(pending || activeRun) && (
        <div className="session-run-strip" aria-live="polite">
          <LoaderCircle className="session-spin" aria-hidden="true" />
          <span>{pending ? '正在发送…' : 'Agent 运行中…'}</span>
          {activeRun ? (
            <button
              type="button"
              className="session-abort-link"
              onClick={onAbort}
              disabled={controlsPending}
            >
              <Square aria-hidden="true" />
              终止
            </button>
          ) : null}
        </div>
      )}
      <div className="session-dock">
        <textarea
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="给 AI 发送消息..."
          disabled={disabled || pending}
          rows={1}
          aria-label="给 AI 发送消息"
        />
        <button
          className="session-send"
          type="button"
          aria-label="发送消息"
          onClick={onSubmit}
          disabled={!draft.trim() || pending || disabled || activeRun}
        >
          {pending ? <LoaderCircle className="session-spin" aria-hidden="true" /> : <Send aria-hidden="true" />}
        </button>
      </div>
      <div className="session-composer-extra">
        <button
          type="button"
          className="session-extra-btn"
          aria-label="插入指令"
          onClick={onSteer}
          disabled={!activeRun || !draft.trim() || controlsPending}
        >
          Steer
        </button>
        <button
          type="button"
          className="session-extra-btn"
          aria-label="排队追问"
          onClick={onFollowUp}
          disabled={!draft.trim() || controlsPending}
        >
          排队
        </button>
        <button
          type="button"
          className="session-extra-btn danger"
          aria-label="终止运行"
          onClick={onAbort}
          disabled={!activeRun || controlsPending}
        >
          终止
        </button>
        <span className="session-extra-hint">{activeRun ? 'running' : 'idle'}</span>
      </div>
    </div>
  )
}
