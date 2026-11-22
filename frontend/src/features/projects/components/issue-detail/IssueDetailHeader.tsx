import { AlertCircle, Trash2, X } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Badge } from '@/shared/ui/data/Badge'
import { readableStateNameOf, type IssueStateNames } from '../../issue-labels'

interface IssueDetailHeaderProps {
  issueNumber: string
  /** 当前阶段编码；详情快照未到达时为 null，此时没有任何阶段事实可展示。 */
  state: string | null
  /** 工作流阶段编码到名称的映射；编码只作为提示保留。 */
  stateNames: IssueStateNames
  isBlocked: boolean
  blockedReason: string | null
  pauseReason: string | null
  deleteDisabled: boolean
  onDelete: () => void
  onClose: () => void
}

/**
 * Issue 详情弹窗头部：编号、阶段徽标、暂停标记，以及删除与关闭入口。
 *
 * <p>徽标只反映当前快照：阶段按工作流名称展示，编码留在提示里；阻塞就是当前阶段的业务表达，
 * 因此只有阻塞时的那一个徽标，提示里保留阻塞原因；暂停标记按 pauseReason 区分人工与出错。
 * 快照未到达时不渲染阶段徽标，工作流未声明的编码也只用通用说法，避免内部编码进入主视觉。
 */
export function IssueDetailHeader({
  issueNumber,
  state,
  stateNames,
  isBlocked,
  blockedReason,
  pauseReason,
  deleteDisabled,
  onDelete,
  onClose,
}: IssueDetailHeaderProps) {
  const { t } = useI18n()
  const stageName = state == null ? null : readableStateNameOf(stateNames, state)
  const stageBadge =
    state == null ? null : isBlocked ? (
      <Badge tone="danger" title={blockedReason ? `${state} · ${blockedReason}` : state}>
        <AlertCircle size={12} aria-hidden="true" />
        {stageName ?? t('projects.state.blocked')}
      </Badge>
    ) : (
      <Badge tone="info" title={state}>
        {stageName ?? t('projects.issue.stageUnknown')}
      </Badge>
    )

  return (
    <div className="modal-header">
      <div className="issue-detail-header-left">
        <span className="issue-number" style={{ fontSize: 'var(--font-size-md)' }}>
          #{issueNumber}
        </span>
        {stageBadge}

        {pauseReason === 'USER' && (
          <Badge tone="warning" title={t('projects.issue.pausedBadgeTitle')}>
            {t('projects.issue.pauseStatus.paused')}
          </Badge>
        )}
        {pauseReason === 'ERROR' && (
          <Badge tone="danger" title={t('projects.issue.errorBadgeTitle')}>
            {t('projects.issue.pauseStatus.error')}
          </Badge>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <Button
          variant="ghost"
          danger
          onClick={onDelete}
          disabled={deleteDisabled}
          title={t('projects.issue.delete')}
          aria-label={t('projects.issue.delete')}
        >
          <Trash2 size={14} aria-hidden="true" />
        </Button>
        <IconButton label={t('projects.close')} onClick={onClose}>
          <X size={16} aria-hidden="true" />
        </IconButton>
      </div>
    </div>
  )
}
