import { Send } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import type { IssueActivityDTO } from '@/shared/api/generated/contracts'
import { IssueActivityTimeline } from './IssueTabPanes'

type ActivityKind = 'COMMENT' | 'INSTRUCTION'

const isActivityKind = (value: string): value is ActivityKind =>
  value === 'COMMENT' || value === 'INSTRUCTION'

interface IssueActivitiesPaneProps {
  activities: IssueActivityDTO[]
  kind: ActivityKind
  body: string
  isPending: boolean
  isWriteBlocked: boolean
  onChangeKind: (kind: ActivityKind) => void
  onChangeBody: (body: string) => void
  onSubmit: (event: React.FormEvent) => void
}

/**
 * 活动 tab：发布 COMMENT/INSTRUCTION 的表单与历史活动时间线。
 *
 * <p>COMMENT 只入时间线；INSTRUCTION 会投递给当前活跃 Run。
 */
export function IssueActivitiesPane({
  activities,
  kind,
  body,
  isPending,
  isWriteBlocked,
  onChangeKind,
  onChangeBody,
  onSubmit,
}: IssueActivitiesPaneProps) {
  const { t } = useI18n()
  const isInstruction = kind === 'INSTRUCTION'
  return (
    <div className="tab-pane">
      <form onSubmit={onSubmit} className="activity-input-card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px', gap: '12px' }}>
          <Select
            compact
            value={kind}
            disabled={isPending}
            aria-label={t('projects.issue.activityTypeAriaLabel')}
            options={[
              { value: 'COMMENT', label: t('projects.issue.activityCommentOption') },
              { value: 'INSTRUCTION', label: t('projects.issue.activityInstructionOption') },
            ]}
            onChange={(value) => {
              if (isActivityKind(value)) {
                onChangeKind(value)
              }
            }}
          />

          <span style={{ fontSize: 'var(--font-size-xs)', color: 'var(--fg-muted)' }}>
            {isInstruction
              ? t('projects.issue.deliverToActiveRun')
              : t('projects.issue.timelineOnlyNoWakeup')}
          </span>
        </div>

        <TextArea
          rows={3}
          placeholder={
            isInstruction
              ? t('projects.issue.instructionPlaceholder')
              : t('projects.issue.commentPlaceholder')
          }
          value={body}
          onChange={(event) => onChangeBody(event.target.value)}
          disabled={isPending}
        />

        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '8px' }}>
          <Button
            type="submit"
            disabled={isWriteBlocked || !body.trim()}
            style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
          >
            <Send size={12} aria-hidden="true" />
            <span>
              {isInstruction
                ? t('projects.issue.dispatchInstruction')
                : t('projects.issue.comment')}
            </span>
          </Button>
        </div>
      </form>

      <IssueActivityTimeline activities={activities} />
    </div>
  )
}
