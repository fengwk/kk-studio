import type { KeyboardEvent } from 'react'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'

/**
 * Canvas prototype dock: + | textarea | send.
 * Gray placeholder/chrome (no green tint).
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
  onAdd,
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
  onAdd?: () => void
}) {
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      onSubmit()
    }
  }

  return (
    <div className="session-composer">
      <div className="session-dock">
        <button
          className="session-dock-add"
          type="button"
          aria-label="添加内容"
          onClick={onAdd}
          disabled={disabled}
        >
          <PlusIcon />
        </button>
        <textarea
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="告诉 Agent 下一步要完成什么…"
          disabled={disabled || pending}
          rows={1}
          aria-label="给 AI 发送消息"
        />
        <button
          className="session-dock-send"
          type="button"
          aria-label="发送消息"
          onClick={onSubmit}
          disabled={!draft.trim() || pending || disabled || activeRun}
        >
          <SendIcon />
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
          className="session-extra-btn"
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
