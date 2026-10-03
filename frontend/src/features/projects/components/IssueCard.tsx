import { AlertCircle, Clock, RotateCcw, ShieldAlert, XCircle } from 'lucide-react'
import type { ProjectIssueSnapshotDTO } from '../types'

export interface IssueCardProps {
  item: ProjectIssueSnapshotDTO
  availableNextStates?: string[]
  onClick: () => void
  onTransition?: (toState: string) => void
  onBlock?: () => void
  onRecover?: () => void
  onReopen?: () => void
  onResolveUnknown?: () => void
}

export function IssueCard({
  item,
  availableNextStates = [],
  onClick,
  onTransition,
  onBlock,
  onRecover,
  onReopen,
  onResolveUnknown,
}: IssueCardProps) {
  const { issue, currentOrLatestRun } = item
  const isBlocked = issue.state === 'BLOCKED' || Boolean(issue.blockedFromState)
  const isUnknown = issue.pauseReason === 'UNKNOWN' || currentOrLatestRun?.status === 'UNKNOWN'
  const isWaiting = currentOrLatestRun?.status === 'WAITING'
  const isFailed = currentOrLatestRun?.status === 'FAILED'
  const isDone = issue.state === 'DONE'

  return (
    <div
      className={`issue-card ${isBlocked ? 'is-blocked' : ''} ${isUnknown ? 'is-unknown' : ''}`}
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
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <span className="issue-number">#{issue.number}</span>
          <span className="badge badge-state">{issue.state}</span>
        </div>

        <div className="issue-badges-row">
          {isBlocked && (
            <span className="badge badge-blocked" title={`阻塞原因: ${issue.blockReason || '未说明'}`}>
              <AlertCircle size={12} aria-hidden="true" />
              BLOCKED
            </span>
          )}
          {isUnknown && (
            <span className="badge badge-unknown" title="需人工核查，暂不能继续">
              <ShieldAlert size={12} aria-hidden="true" />
              UNKNOWN
            </span>
          )}
          {issue.pauseReason === 'USER' && (
            <span className="badge badge-paused" title="已人工暂停">
              PAUSED
            </span>
          )}
          {issue.pauseReason === 'ERROR' && (
            <span className="badge badge-failed" title="发生错误已暂停">
              ERROR
            </span>
          )}
          {isWaiting && (
            <span className="badge badge-waiting" title="等待处理">
              <Clock size={12} aria-hidden="true" />
              WAITING
            </span>
          )}
          {isFailed && (
            <span className="badge badge-failed" title="执行失败">
              <XCircle size={12} aria-hidden="true" />
              FAILED
            </span>
          )}
          {issue.archivedAt && (
            <span className="badge badge-archived">已归档</span>
          )}
        </div>
      </div>

      <h4 className="issue-title">{issue.title}</h4>

      {currentOrLatestRun && (
        <div className="issue-card-meta-row">
          <span className="badge badge-run">
            #{currentOrLatestRun.ordinal} {currentOrLatestRun.state}
            {currentOrLatestRun.agentName ? ` · ${currentOrLatestRun.agentName}` : ''}
            {` · ${currentOrLatestRun.status}`}
          </span>
        </div>
      )}

      {issue.blockReason && isBlocked && (
        <p className="issue-block-reason" title={issue.blockReason}>
          原因: {issue.blockReason}
        </p>
      )}

      {/* 快捷操作区 */}
      <div
        className="issue-card-quick-actions"
        onClick={(e) => e.stopPropagation()}
      >
        {isUnknown && onResolveUnknown && (
          <button
            type="button"
            className="action-pill danger"
            onClick={onResolveUnknown}
            title="核查外部副作用，解除 UNKNOWN"
          >
            <ShieldAlert size={12} aria-hidden="true" />
            <span>人工核查</span>
          </button>
        )}

        {isBlocked && onRecover && (
          <button
            type="button"
            className="action-pill primary"
            onClick={onRecover}
            title={`恢复至 ${issue.blockedFromState || '原阶段'}`}
          >
            <RotateCcw size={12} aria-hidden="true" />
            <span>恢复</span>
          </button>
        )}

        {isDone && onReopen && (
          <button
            type="button"
            className="action-pill"
            onClick={onReopen}
            title="重新打开已完成的 Issue 回到 INIT"
          >
            <RotateCcw size={12} aria-hidden="true" />
            <span>重开</span>
          </button>
        )}

        {!isBlocked && !isDone && onBlock && (
          <button
            type="button"
            className="action-pill"
            onClick={onBlock}
            title="设置业务阻塞原因"
          >
            <AlertCircle size={12} aria-hidden="true" />
            <span>阻塞</span>
          </button>
        )}

        {/* 根据工作流 next 转移白名单提供的快捷流转 */}
        {!isBlocked && availableNextStates.length > 0 && onTransition && (
          <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
            {availableNextStates.map((nextState) => (
              <button
                key={nextState}
                type="button"
                className="action-pill primary"
                onClick={() => onTransition(nextState)}
                title={`流转到 ${nextState}`}
              >
                <span>→ {nextState}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
