import { Clock3, Send, Square, StepForward } from 'lucide-react'
import type { KeyboardEvent } from 'react'

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
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      onSubmit()
    }
  }

  return (
    <div className="chat-input-area session-composer">
      <div className="chat-input-container">
        <textarea
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="给 AI 发送消息..."
          disabled={disabled || pending}
        />
        <div className="chat-input-tools">
          <div className="run-hint">
            <Clock3 aria-hidden="true" />
            <span>{activeRun ? 'running' : 'idle'}</span>
          </div>
          <button
            className="control-btn"
            type="button"
            aria-label="插入指令"
            onClick={onSteer}
            disabled={!activeRun || !draft.trim() || controlsPending}
          >
            Steer
          </button>
          <button
            className="control-btn"
            type="button"
            aria-label="排队追问"
            onClick={onFollowUp}
            disabled={!draft.trim() || controlsPending}
          >
            <StepForward aria-hidden="true" />
          </button>
          <button
            className="control-btn danger"
            type="button"
            aria-label="终止运行"
            onClick={onAbort}
            disabled={!activeRun || controlsPending}
          >
            <Square aria-hidden="true" />
          </button>
          <button
            className="send-btn"
            type="button"
            aria-label="发送消息"
            onClick={onSubmit}
            disabled={!draft.trim() || pending || disabled || activeRun}
          >
            <Send aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>
  )
}
