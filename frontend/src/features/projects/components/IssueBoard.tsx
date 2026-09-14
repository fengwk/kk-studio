import { useMemo, useState } from 'react'
import { Plus, Search, Ban } from 'lucide-react'
import { Checkbox } from '@/shared/ui/console/Checkbox'
import type { IssueStatus, ProjectIssueSnapshotDTO } from '../types'
import { IssueCard } from './IssueCard'

export interface IssueBoardProps {
  issues: ProjectIssueSnapshotDTO[]
  onSelectIssue: (issueId: string) => void
  onCreateIssue: () => void
  onChangeIssueStatus: (issueId: string, expectedVersion: string, status: IssueStatus) => void
  onCancelIssue: (issueId: string, expectedVersion: string) => void
  onArchiveIssue: (issueId: string, expectedVersion: string) => void
  onUnarchiveIssue: (issueId: string, expectedVersion: string) => void
}

interface ColumnDefinition {
  key: string
  title: string
  predicate: (item: ProjectIssueSnapshotDTO) => boolean
}

export function IssueBoard({
  issues,
  onSelectIssue,
  onCreateIssue,
  onChangeIssueStatus,
  onCancelIssue,
  onArchiveIssue,
  onUnarchiveIssue,
}: IssueBoardProps) {
  const [searchQuery, setSearchQuery] = useState('')
  const [showCanceled, setShowCanceled] = useState(false)

  const filteredIssues = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) {
      return issues
    }
    return issues.filter(
      (item) =>
        item.issue.title.toLowerCase().includes(q) ||
        item.issue.number.includes(q) ||
        (item.issue.assigneeAgentName && item.issue.assigneeAgentName.toLowerCase().includes(q)),
    )
  }, [issues, searchQuery])

  const columns: ColumnDefinition[] = useMemo(
    () => [
      {
        key: 'BACKLOG',
        title: '需求池 (Backlog)',
        predicate: (item) => item.issue.status === 'BACKLOG',
      },
      {
        key: 'TODO',
        title: '待办 (To Do)',
        predicate: (item) => item.issue.status === 'TODO',
      },
      {
        key: 'IN_PROGRESS',
        title: '执行中 (In Progress)',
        predicate: (item) =>
          item.issue.status === 'IN_PROGRESS' &&
          item.currentOrLatestRun?.status !== 'WAITING_HUMAN',
      },
      {
        key: 'WAITING_HUMAN',
        title: '等待人类 (Waiting Human)',
        predicate: (item) =>
          item.issue.status === 'IN_PROGRESS' &&
          item.currentOrLatestRun?.status === 'WAITING_HUMAN',
      },
      {
        key: 'IN_REVIEW',
        title: '审核中 (In Review)',
        predicate: (item) => item.issue.status === 'IN_REVIEW',
      },
      {
        key: 'DONE',
        title: '已完成 (Done)',
        predicate: (item) => item.issue.status === 'DONE',
      },
    ],
    [],
  )

  const canceledIssues = useMemo(() => {
    return filteredIssues.filter((item) => item.issue.status === 'CANCELED')
  }, [filteredIssues])

  return (
    <div className="project-board-section">
      <div className="project-board-toolbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
          <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
            <Search
              size={14}
              style={{ position: 'absolute', left: '10px', color: 'var(--fg-muted)' }}
              aria-hidden="true"
            />
            <input
              type="text"
              className="projects-search-input"
              style={{ paddingLeft: '32px' }}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="搜索 Issue 标题 / 编号..."
              aria-label="搜索 Issue"
            />
          </div>

          <Checkbox
            checked={showCanceled}
            onChange={setShowCanceled}
            label={`显示已取消 (${canceledIssues.length})`}
          />
        </div>

        <button
          type="button"
          className="btn-primary"
          onClick={onCreateIssue}
          style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
        >
          <Plus size={14} aria-hidden="true" />
          <span>新建 Issue</span>
        </button>
      </div>

      <div className="project-board-scroll-container">
        {columns.map((col) => {
          const colItems = filteredIssues.filter(col.predicate)
          return (
            <div key={col.key} className="board-column" data-column-key={col.key}>
              <div className="board-column-header">
                <span>{col.title}</span>
                <span className="badge badge-status">{colItems.length}</span>
              </div>
              <div className="board-column-cards">
                {colItems.length === 0 ? (
                  <div style={{ padding: '16px', textAlign: 'center', color: 'var(--fg-muted)', fontSize: '0.8125rem' }}>
                    无 Issue
                  </div>
                ) : (
                  colItems.map((item) => (
                    <IssueCard
                      key={item.issue.id}
                      item={item}
                      onClick={() => onSelectIssue(item.issue.id)}
                      onChangeStatus={(status) =>
                        onChangeIssueStatus(item.issue.id, item.issue.version, status)
                      }
                      onCancel={() => onCancelIssue(item.issue.id, item.issue.version)}
                      onArchive={() => onArchiveIssue(item.issue.id, item.issue.version)}
                      onUnarchive={() => onUnarchiveIssue(item.issue.id, item.issue.version)}
                    />
                  ))
                )}
              </div>
            </div>
          )
        })}

        {showCanceled && (
          <div className="canceled-issues-lane" data-column-key="CANCELED">
            <div
              className="board-column-header"
              style={{ color: '#f87171', borderBottomColor: 'rgba(239, 68, 68, 0.2)' }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <Ban size={14} aria-hidden="true" />
                <span>已取消 (Canceled)</span>
              </div>
              <span className="badge badge-failed">{canceledIssues.length}</span>
            </div>
            <div className="board-column-cards">
              {canceledIssues.length === 0 ? (
                <div style={{ padding: '16px', textAlign: 'center', color: 'var(--fg-muted)', fontSize: '0.8125rem' }}>
                  无已取消 Issue
                </div>
              ) : (
                canceledIssues.map((item) => (
                  <IssueCard
                    key={item.issue.id}
                    item={item}
                    onClick={() => onSelectIssue(item.issue.id)}
                    onChangeStatus={(status) =>
                      onChangeIssueStatus(item.issue.id, item.issue.version, status)
                    }
                    onArchive={() => onArchiveIssue(item.issue.id, item.issue.version)}
                    onUnarchive={() => onUnarchiveIssue(item.issue.id, item.issue.version)}
                  />
                ))
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
