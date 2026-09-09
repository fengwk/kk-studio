import { useEffect, useId, useRef, useState } from 'react'
import { Check } from 'lucide-react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import { useI18n } from '@/shared/i18n'

/**
 * 持久 Session/Thread 实体的单输入重命名面板。
 *
 * 只用于已持久化实体（绝不用于创建前草稿）：initialName 是服务端权威当前名称；
 * busy 为真时输入禁用且保存不可用（名称仍在解析）；pending 期间禁用保存与关闭；
 * 提交失败时保留用户输入并就地显示错误；提交成功由父组件卸载本面板。
 */
export function NameRenamePanel({
  title,
  initialName,
  pending = false,
  error = null,
  busy = false,
  onSubmit,
  onClose,
}: {
  title: string
  /** 当前权威名称；null 表示仍在 on-demand 解析（session 摘要未到达）。 */
  initialName: string | null
  pending?: boolean
  error?: string | null
  /** 当前名称仍在解析（摘要未到达），输入与保存禁用。 */
  busy?: boolean
  onSubmit: (name: string) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  // useId 保证多 Pane 同时打开重命名面板时 label/input 关联唯一。
  const inputId = useId()
  const [name, setName] = useState(initialName ?? '')
  const [lastPrefill, setLastPrefill] = useState<string | null>(initialName)
  const inputRef = useRef<HTMLInputElement>(null)

  // on-demand 解析到达后预填一次；后续用户编辑不再被覆盖。
  useEffect(() => {
    if (initialName != null && initialName !== lastPrefill) {
      setName(initialName)
      setLastPrefill(initialName)
    }
  }, [initialName, lastPrefill])

  // busy 转为可编辑时聚焦并全选，方便直接覆盖当前名称。
  useEffect(() => {
    if (!busy) {
      inputRef.current?.focus({ preventScroll: true })
      inputRef.current?.select()
    }
  }, [busy])

  const trimmed = name.trim()
  const canSave = !pending && !busy && trimmed.length > 0

  return (
    <ThreadInteractionPanel
      title={title}
      className="name-rename-panel"
      closeDisabled={pending || busy}
      busy={busy || pending}
      onClose={onClose}
      onKeyDown={(event) => {
        if (event.nativeEvent.isComposing || event.keyCode === 229) {
          return
        }
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          // pending（PUT 在途）/busy（解析中）期间禁止关闭：关闭会清空
          // renameTargetRef，PUT 成功返回后将跳过 cache patch 与失效，
          // 留下服务端已改名而本地陈旧的竞态。
          if (!pending && !busy) {
            onClose()
          }
          return
        }
        if (event.key === 'Enter') {
          event.preventDefault()
          event.stopPropagation()
          if (canSave) {
            void onSubmit(trimmed)
          }
        }
      }}
    >
      <div className="name-rename-body">
        <label className="name-rename-field" htmlFor={inputId}>
          <span>{t('ai.runtime.rename.nameLabel')}</span>
        </label>
        <input
          id={inputId}
          ref={inputRef}
          type="text"
          value={name}
          placeholder={busy ? t('ai.runtime.rename.loadingName') : t('ai.runtime.rename.namePlaceholder')}
          aria-label={t('ai.runtime.rename.nameLabel')}
          disabled={pending || busy}
          onChange={(event) => setName(event.target.value)}
        />
        {error ? (
          <div className="name-rename-error" role="alert">{error}</div>
        ) : null}
      </div>
      <div className="name-rename-actions">
        <button type="button" className="ghost-btn" onClick={onClose} disabled={pending || busy}>
          {t('shared.cancel')}
        </button>
        <button
          type="button"
          className="btn-primary name-rename-save"
          disabled={!canSave}
          onClick={() => void onSubmit(trimmed)}
        >
          <Check size={13} aria-hidden="true" />
          {t('ai.runtime.rename.save')}
        </button>
      </div>
    </ThreadInteractionPanel>
  )
}
