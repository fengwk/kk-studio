import { AlertCircle, Bot, Clock, RotateCcw, User, XCircle } from 'lucide-react'
import type { IssueStatus, ProjectIssueSnapshotDTO } from '../types'

export interface IssueCardProps {
  item: ProjectIssueSnapshotDTO
  maxReviewRejections: string
  onClick: () => void
  onChangeStatus?: (targetStatus: IssueStatus) => void
  onCancel?: () => void
  onArchive?: () => void
  onUnarchive?: () => void
}

export function IssueCard({
  item,
  maxReviewRejections,
  onClick,
  onChangeStatus,
  onCancel,
  onArchive,
  onUnarchive,
}: IssueCardProps) {
  const { issue, blocked, currentOrLatestRun } = item
  const isBlocked = blocked || issue.status === 'BLOCKED'
  const isWaitingHuman = currentOrLatestRun?.status === 'WAITING_HUMAN'
  const isFailed = currentOrLatestRun?.status === 'FAILED'
  const isUnknown = currentOrLatestRun?.status === 'UNKNOWN'
  const isTerminal = issue.status === 'DONE' || issue.status === 'CANCELED'

  return (
    <div
      className={`issue-card ${isBlocked ? 'is-blocked' : ''} ${isWaitingHuman ? 'is-waiting' : ''}`}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onClick()
        }
      }}
      aria-label={`Issue #${issue.number} ${issue.title}`}
    >
      <div className="issue-card-header">
        <span className="issue-number">#{issue.number}</span>
        <div className="issue-badges-row">
          {isBlocked && (
            <span className="badge badge-blocked" title="Issue 处于阻塞状态">
              <AlertCircle size={12} aria-hidden="true" />
              BLOCKED
            </span>
          )}
          {isBlocked && (
            <span
              className="badge badge-blocked-rejections"
              title={`打回次数: ${item.reviewRejectionCount} / ${maxReviewRejections}`}
            >
              {item.reviewRejectionCount} / {maxReviewRejections}
            </span>
          )}
          {isWaitingHuman && (
            <span className="badge badge-waiting" title="等待人类输入">
              <Clock size={12} aria-hidden="true" />
              WAITING
            </span>
          )}
          {isFailed && (
            <span className="badge badge-failed" title="Run 执行失败">
              <XCircle size={12} aria-hidden="true" />
              FAILED
            </span>
          )}
          {isUnknown && (
            <span className="badge badge-unknown" title="Run 状态未知">
              UNKNOWN
            </span>
          )}
          {issue.archivedAt && (
            <span className="badge badge-archived">已归档</span>
          )}
        </div>
      </div>

      <h4 className="issue-title">{issue.title}</h4>

      {currentOrLatestRun && (
        <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
          <span className="badge badge-run">
            {currentOrLatestRun.role === 'REVIEWER' ? '审核' : '执行'} #{currentOrLatestRun.ordinal}
            {currentOrLatestRun.outcome ? ` · ${currentOrLatestRun.outcome}` : ` · ${currentOrLatestRun.status}`}
          </span>
        </div>
      )}

      <div className="issue-card-meta">
        <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
          {issue.assigneeAgentName ? (
            <>
              <Bot size={12} aria-hidden="true" />
              <span>{issue.assigneeAgentName}</span>
            </>
          ) : (
            <>
              <User size={12} aria-hidden="true" />
              <span>未指定</span>
            </>
          )}
        </span>
        <span style={{ fontSize: '0.75rem' }}>
          {issue.reviewerAgentName ? `审: ${issue.reviewerAgentName}` : '人审'}
        </span>
      </div>

      <div
        className="issue-card-quick-actions"
        onClick={(e) => e.stopPropagation()}
      >
        {issue.status === 'BACKLOG' && onChangeStatus && (
          <button
            type="button"
            className="quick-action-btn"
            onClick={() => onChangeStatus('TODO')}
            title="移至待办"
          >
            → TODO
          </button>
        )}
        {issue.status === 'TODO' && onChangeStatus && (
          <button
            type="button"
            className="quick-action-btn"
            onClick={() => onChangeStatus('BACKLOG')}
            title="放回需求池"
          >
            ← BACKLOG
          </button>
        )}
        {isTerminal && onChangeStatus && (
          <button
            type="button"
            className="quick-action-btn"
            onClick={() => onChangeStatus('TODO')}
            title="重新打开至 TODO"
          >
            <RotateCcw size={11} style={{ marginRight: '2px' }} aria-hidden="true" />
            Reopen
          </button>
        )}
        {!isTerminal && onCancel && (
          <button
            type="button"
            className="quick-action-btn"
            onClick={onCancel}
            title="取消此 Issue"
          >
            取消
          </button>
        )}
        {isTerminal && !issue.archivedAt && onArchive && (
          <button
            type="button"
            className="quick-action-btn"
            onClick={onArchive}
            title="归档此 Issue"
          >
            归档
          </button>
        )}
        {isTerminal && issue.archivedAt && onUnarchive && (
          <button
            type="button"
            className="quick-action-btn"
            onClick={onUnarchive}
            title="取消归档"
          >
            恢复
          </button>
        )}
      </div>
    </div>
  )
}
