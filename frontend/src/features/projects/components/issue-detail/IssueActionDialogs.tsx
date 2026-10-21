import type { ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { MAX_RUNS } from '../../workflow-draft'

interface TextActionDialogProps {
  title: string
  headerIcon?: ReactNode
  description?: string
  fieldId: string
  fieldLabel: string
  required?: boolean
  rows: number
  value: string
  placeholder: string
  onChange: (value: string) => void
  submitLabel: string
  pendingLabel: string
  danger?: boolean
  isPending: boolean
  isBlocked: boolean
  onSubmit: (e: React.FormEvent) => void
  onClose: () => void
}

/** 只含一个多行文本字段的写操作对话框（阻塞原因、人工核查结论、终止说明）。 */
export function TextActionDialog({
  title,
  headerIcon,
  description,
  fieldId,
  fieldLabel,
  required,
  rows,
  value,
  placeholder,
  onChange,
  submitLabel,
  pendingLabel,
  danger,
  isPending,
  isBlocked,
  onSubmit,
  onClose,
}: TextActionDialogProps) {
  const { t } = useI18n()
  return (
    <Dialog className="sub-card" title={title} headerIcon={headerIcon} pending={isBlocked} onClose={onClose}>
      <form className="modal-card-form" onSubmit={onSubmit}>
        <div className="modal-body">
          {description && (
            <p style={{ fontSize: 'var(--font-size-sm)', color: 'var(--fg-muted)', margin: '0 0 12px 0' }}>
              {description}
            </p>
          )}
          <label className="edit-project-field" htmlFor={fieldId}>
            <FieldLabel required={required}>{fieldLabel}</FieldLabel>
            <TextArea
              id={fieldId}
              rows={rows}
              value={value}
              onChange={(e) => onChange(e.target.value)}
              placeholder={placeholder}
              autoFocus
            />
          </label>
        </div>
        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose}>
            {t('projects.cancel')}
          </Button>
          <Button type="submit" danger={danger} disabled={isBlocked}>
            {isPending ? pendingLabel : submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export function ResetBudgetDialog({
  state,
  maxRuns,
  onStateChange,
  onMaxRunsChange,
  isPending,
  isBlocked,
  onSubmit,
  onClose,
}: {
  state: string
  maxRuns: number
  onStateChange: (value: string) => void
  onMaxRunsChange: (value: number) => void
  isPending: boolean
  isBlocked: boolean
  onSubmit: (e: React.FormEvent) => void
  onClose: () => void
}) {
  const { t } = useI18n()
  return (
    <Dialog
      className="sub-card"
      title={t('projects.issue.resetStageBudgetAction')}
      pending={isBlocked}
      onClose={onClose}
    >
      <form className="modal-card-form" onSubmit={onSubmit}>
        <div className="modal-body">
          <label className="edit-project-field" htmlFor="reset-budget-state">
            <FieldLabel required>{t('projects.issue.budgetStageFieldLabel')}</FieldLabel>
            <TextInput
              id="reset-budget-state"
              value={state}
              onChange={(e) => onStateChange(e.target.value)}
              placeholder={t('projects.issue.budgetStagePlaceholder')}
            />
          </label>
          <label className="edit-project-field" htmlFor="reset-budget-max-runs">
            <FieldLabel required>{t('projects.issue.budgetMaxRunsFieldLabel')}</FieldLabel>
            <NumberInput
              id="reset-budget-max-runs"
              min={1}
              max={MAX_RUNS}
              value={String(maxRuns)}
              onChange={(next) => onMaxRunsChange(next === '' ? 0 : Number(next))}
            />
          </label>
        </div>
        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose}>
            {t('projects.cancel')}
          </Button>
          <Button type="submit" disabled={isBlocked}>
            {isPending ? t('projects.issue.resettingBudget') : t('projects.issue.confirmResetBudget')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export function DeleteIssueDialog({
  issueNumber,
  isPending,
  isBlocked,
  onConfirm,
  onClose,
}: {
  issueNumber: number | string
  isPending: boolean
  isBlocked: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  const { t } = useI18n()
  return (
    <Dialog
      className="sub-card"
      title={t('projects.issue.deleteDialogTitle', { number: issueNumber })}
      pending={isBlocked}
      onClose={onClose}
    >
      <div className="modal-body">
        <p style={{ margin: 0, color: 'var(--fg)' }}>
          {t('projects.issue.deleteConfirmDesc')}
        </p>
      </div>
      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose}>
          {t('projects.cancel')}
        </Button>
        <Button danger disabled={isBlocked} onClick={onConfirm}>
          {isPending ? t('projects.issue.deleting') : t('projects.delete.confirm')}
        </Button>
      </div>
    </Dialog>
  )
}
