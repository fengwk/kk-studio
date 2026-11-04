import { Pencil } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'

type Issue = IssueDetailDTO['issue']

interface IssueSpecPaneProps {
  issue: Issue
  isEditing: boolean
  draftTitle: string
  draftDescription: string
  isWriteBlocked: boolean
  isActionPending: boolean
  onStartEdit: () => void
  onCancel: () => void
  onTitleChange: (value: string) => void
  onDescriptionChange: (value: string) => void
  onSubmit: (event: React.FormEvent) => void
}

/** Spec tab：只读事实与编辑草稿。版本冲突与保存由父级统一裁决。 */
export function IssueSpecPane({
  issue,
  isEditing,
  draftTitle,
  draftDescription,
  isWriteBlocked,
  isActionPending,
  onStartEdit,
  onCancel,
  onTitleChange,
  onDescriptionChange,
  onSubmit,
}: IssueSpecPaneProps) {
  const { t } = useI18n()
  return (
    <div className="tab-pane">
      {isEditing ? (
        <form onSubmit={onSubmit} className="spec-edit-form">
          <label className="edit-project-field" htmlFor="issue-spec-title">
            <FieldLabel required>{t('projects.issue.specTitleLabel')}</FieldLabel>
            <TextInput
              id="issue-spec-title"
              value={draftTitle}
              invalid={!draftTitle.trim()}
              onChange={(event) => onTitleChange(event.target.value)}
              disabled={isWriteBlocked}
              autoFocus
            />
          </label>
          <label className="edit-project-field" htmlFor="issue-spec-desc">
            <FieldLabel>{t('projects.issue.specDescLabel')}</FieldLabel>
            <TextArea
              id="issue-spec-desc"
              rows={8}
              value={draftDescription}
              onChange={(event) => onDescriptionChange(event.target.value)}
              disabled={isWriteBlocked}
            />
          </label>
          <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
            <Button variant="ghost" onClick={onCancel} disabled={isActionPending}>
              {t('projects.cancel')}
            </Button>
            <Button type="submit" disabled={isWriteBlocked}>
              {isActionPending ? t('projects.issue.saving') : t('projects.issue.saveChanges')}
            </Button>
          </div>
        </form>
      ) : (
        <IssueSpecView issue={issue} editDisabled={isWriteBlocked} onEdit={onStartEdit} />
      )}
    </div>
  )
}

/** Spec 只读视图：标题、描述与状态元数据。 */
export function IssueSpecView({
  issue,
  editDisabled,
  onEdit,
}: {
  issue: Issue
  editDisabled: boolean
  onEdit: () => void
}) {
  const { t } = useI18n()
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 className="spec-title">{issue.title}</h2>
        <Button
          variant="ghost"
          onClick={onEdit}
          disabled={editDisabled}
          title={t('projects.issue.editSpecTitle')}
        >
          <Pencil size={14} aria-hidden="true" />
          <span>{t('projects.issue.editSpec')}</span>
        </Button>
      </div>

      <div className="spec-desc-block">
        {issue.description ? (
          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{issue.description}</div>
        ) : (
          <span style={{ color: 'var(--fg-muted)' }}>{t('projects.issue.noSpecDesc')}</span>
        )}
      </div>

      <div className="spec-meta-grid">
        <div className="meta-item">
          <span className="meta-label">{t('projects.issue.currentStageLabel')}</span>
          <span className="meta-value"><code>{issue.state}</code></span>
        </div>
        <div className="meta-item">
          <span className="meta-label">{t('projects.issue.expectedVersionLabel')}</span>
          <span className="meta-value"><code>{issue.version}</code></span>
        </div>
        {issue.blockedFromState && (
          <div className="meta-item">
            <span className="meta-label">{t('projects.issue.blockedFromStageLabel')}</span>
            <span className="meta-value">{issue.blockedFromState}</span>
          </div>
        )}
        {issue.blockReason && (
          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
            <span className="meta-label">{t('projects.issue.blockReasonLabel')}</span>
            <span className="meta-value" style={{ color: 'var(--danger)' }}>{issue.blockReason}</span>
          </div>
        )}
        {issue.pauseDetail && (
          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
            <span className="meta-label">{t('projects.issue.pauseDetailLabel')}</span>
            <span className="meta-value">{issue.pauseDetail}</span>
          </div>
        )}
      </div>
    </div>
  )
}
