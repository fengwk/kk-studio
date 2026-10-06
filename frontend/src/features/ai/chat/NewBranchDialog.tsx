import { useEffect, useState } from 'react'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { normalizeThreadName } from '@/features/ai/chat/thread-name'
import { useI18n } from '@/shared/i18n'
import '@/features/ai/chat/new-branch-dialog.css'

export interface BranchDestinationOption {
  value: string
  label: string
}

/**
 * 新建分支对话框：/tree 历史树与回合 footer 的 GitBranch 图标共用的唯一命名与目标流程。
 *
 * 名称是创建 target 的必需事实（进入 creationRequestHash），提交前按后端 Names 规则
 * 规范化；目标位置（打开位置）选择 1..9 中的任一 pane，隐藏 pane 由 workspace 负责显露。
 * 覆盖未发送草稿的确认与在途阻止由 workspace 决定，本组件只负责呈现。
 */
export function NewBranchDialog({
  destinations,
  defaultDestination,
  pending = false,
  formError = null,
  overwriteWarning = null,
  onConfirm,
  onClose,
}: {
  destinations: BranchDestinationOption[]
  /** 默认目标 pane（通常是触发分支的 pane）。 */
  defaultDestination: string
  pending?: boolean
  formError?: string | null
  /** 非空表示目标位置有未发送草稿，需要再次确认覆盖。 */
  overwriteWarning?: string | null
  onConfirm: (input: { name: string; paneId: string }) => void
  onClose: () => void
}) {
  const { t } = useI18n()
  const [name, setName] = useState('')
  const [paneId, setPaneId] = useState(
    () => destinations.find((option) => option.value === defaultDestination)?.value
      ?? destinations[0]?.value
      ?? '',
  )
  const [nameError, setNameError] = useState<string | null>(null)

  // 目标缺省值变化（如触发分支的 pane 改变）时收敛到合法值。
  useEffect(() => {
    setPaneId((current) => destinations.some((option) => option.value === current)
      ? current
      : destinations.find((option) => option.value === defaultDestination)?.value
        ?? destinations[0]?.value
        ?? '')
  }, [defaultDestination, destinations])

  function submit() {
    const normalized = normalizeThreadName(name)
    if (normalized == null) {
      setNameError(name.trim().length === 0
        ? t('ai.chat.branch.nameRequired')
        : t('ai.chat.branch.nameTooLong'))
      return
    }
    setNameError(null)
    onConfirm({ name: normalized, paneId })
  }

  return (
    <Dialog
      className="new-branch-dialog"
      title={t('ai.chat.branch.newBranch')}
      closeLabel={t('shared.close')}
      pending={pending}
      onClose={onClose}
    >
      <form
        className="new-branch-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        {formError ? (
          <div className="form-error-banner" role="alert">{formError}</div>
        ) : null}
        <label className={`form-group${nameError ? ' is-error' : ''}`}>
          <FieldLabel required>{t('ai.chat.branch.name')}</FieldLabel>
          <input
            className="new-branch-name"
            value={name}
            autoFocus
            aria-label={t('ai.chat.branch.name')}
            aria-invalid={nameError != null}
            onChange={(event) => {
              setName(event.target.value)
              setNameError(null)
            }}
          />
          {nameError ? <span className="field-error">{nameError}</span> : null}
        </label>
        <label className="form-group">
          <FieldLabel required>{t('ai.chat.branch.destination')}</FieldLabel>
          <Select
            aria-label={t('ai.chat.branch.destination')}
            value={paneId}
            options={destinations}
            onChange={setPaneId}
          />
        </label>
        {overwriteWarning ? (
          <div className="new-branch-overwrite" role="alert">{overwriteWarning}</div>
        ) : null}
        <div className="new-branch-actions">
          <button type="button" className="ghost-btn" onClick={onClose} disabled={pending}>
            {t('shared.cancel')}
          </button>
          <button type="submit" className="btn-primary" disabled={pending || paneId === ''}>
            {overwriteWarning ? t('ai.chat.branch.confirmOverwrite') : t('ai.chat.branch.openDraft')}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
