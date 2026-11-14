import { AlertCircle, Trash2, X } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Badge } from '@/shared/ui/data/Badge'

interface IssueDetailHeaderProps {
  issueNumber: string
  state: string
  isBlocked: boolean
  blockedReason: string | null
  pauseReason: string | null
  deleteDisabled: boolean
  onDelete: () => void
  onClose: () => void
}

/**
 * Issue 详情弹窗头部：编号、状态徽标、阻塞与暂停标记，以及删除与关闭入口。
 *
 * <p>徽标只反映当前快照：阻塞标记优先显示阻塞原因，暂停标记按 pauseReason 区分 USER/ERROR。
 */
export function IssueDetailHeader({
  issueNumber,
  state,
  isBlocked,
  blockedReason,
  pauseReason,
  deleteDisabled,
  onDelete,
  onClose,
}: IssueDetailHeaderProps) {
  const { t } = useI18n()
  return (
    <div className="modal-header">
      <div className="issue-detail-header-left">
        <span className="issue-number" style={{ fontSize: 'var(--font-size-md)' }}>
          #{issueNumber}
        </span>
        <Badge tone="info" mono>{state}</Badge>

        {isBlocked && (
          <Badge tone="danger" title={blockedReason ?? t('projects.state.blocked')}>
            <AlertCircle size={12} aria-hidden="true" />
            BLOCKED
          </Badge>
        )}
        {pauseReason === 'USER' && <Badge tone="warning">PAUSED</Badge>}
        {pauseReason === 'ERROR' && <Badge tone="danger">ERROR</Badge>}
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
