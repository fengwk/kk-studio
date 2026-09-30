import { useMemo, useState } from 'react'
import { Plus, Search } from 'lucide-react'
import { Checkbox } from '@/shared/ui/console/Checkbox'
import type { ProjectIssueSnapshotDTO, ProjectWorkflowDTO } from '../types'
import { IssueCard } from './IssueCard'

export interface IssueBoardProps {
  workflow?: ProjectWorkflowDTO
  issues: ProjectIssueSnapshotDTO[]
  onSelectIssue: (issueId: string) => void
  onCreateIssue: () => void
  onTransitionIssue: (issueId: string, expectedVersion: string, toState: string) => void
  onBlockIssue: (issueId: string, expectedVersion: string) => void
  onRecoverIssue: (issueId: string, expectedVersion: string) => void
  onReopenIssue: (issueId: string, expectedVersion: string) => void
  onResolveUnknownIssue: (issueId: string, expectedVersion: string) => void
}

export function IssueBoard({
  workflow,
  issues,
  onSelectIssue,
  onCreateIssue,
  onTransitionIssue,
  onBlockIssue,
  onRecoverIssue,
  onReopenIssue,
  onResolveUnknownIssue,
}: IssueBoardProps) {
  const [searchQuery, setSearchQuery] = useState('')
  const [includeArchived, setIncludeArchived] = useState(false)

  const states = useMemo(() => workflow?.states ?? [
    { state: 'INIT', name: '待开始', next: ['DONE'] },
    { state: 'BLOCKED', name: '业务阻塞' },
    { state: 'DONE', name: '完成' },
  ], [workflow])

  // 状态与允许转移的映射表
  const stateNextMap = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const st of states) {
      map.set(st.state, st.next ?? [])
    }
    return map
  }, [states])

  // 过滤后的 Issue 列表
  const filteredIssues = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    return issues.filter((item) => {
      if (!includeArchived && item.issue.archivedAt) {
        return false
      }
      if (!q) {
        return true
      }
      return (
        item.issue.title.toLowerCase().includes(q) ||
        item.issue.number.includes(q) ||
        item.issue.state.toLowerCase().includes(q)
      )
    })
  }, [issues, searchQuery, includeArchived])

  // 按状态自然 token 组织 Issue 列表
  const issuesByState = useMemo(() => {
    const map = new Map<string, ProjectIssueSnapshotDTO[]>()
    for (const st of states) {
      map.set(st.state, [])
    }
    const unknownStateIssues: ProjectIssueSnapshotDTO[] = []

    for (const item of filteredIssues) {
      const stateList = map.get(item.issue.state)
      if (stateList) {
        stateList.push(item)
      } else {
        unknownStateIssues.push(item)
      }
    }
    return { map, unknownStateIssues }
  }, [states, filteredIssues])

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
              placeholder="搜索 Issue 标题 / 编号 / 状态..."
              aria-label="搜索 Issue"
            />
          </div>

          <Checkbox
            checked={includeArchived}
            onChange={setIncludeArchived}
            label="显示已归档"
          />
        </div>

        <button
          type="button"
          className="btn-primary"
          style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
          onClick={onCreateIssue}
        >
          <Plus size={14} aria-hidden="true" />
          <span>新建 Issue</span>
        </button>
      </div>

      {/* 状态自然 token 动态看板列 */}
      <div className="project-board-columns">
        {states.map((st) => {
          const colIssues = issuesByState.map.get(st.state) ?? []
          return (
            <div key={st.state} className="project-board-column" data-state={st.state}>
              <div className="column-header">
                <div className="column-header-left">
                  <h3 className="column-title">{st.name}</h3>
                  <span className="badge badge-state">{st.state}</span>
                </div>
                <span className="column-count">{colIssues.length}</span>
              </div>

              <div className="column-body">
                {colIssues.length === 0 ? (
                  <div className="column-empty">
                    <span>暂无 Issue</span>
                  </div>
                ) : (
                  colIssues.map((item) => (
                    <IssueCard
                      key={item.issue.id}
                      item={item}
                      availableNextStates={stateNextMap.get(item.issue.state) ?? []}
                      onClick={() => onSelectIssue(item.issue.id)}
                      onTransition={(toState) =>
                        onTransitionIssue(item.issue.id, item.issue.version, toState)
                      }
                      onBlock={() => onBlockIssue(item.issue.id, item.issue.version)}
                      onRecover={() => onRecoverIssue(item.issue.id, item.issue.version)}
                      onReopen={() => onReopenIssue(item.issue.id, item.issue.version)}
                      onResolveUnknown={() =>
                        onResolveUnknownIssue(item.issue.id, item.issue.version)
                      }
                    />
                  ))
                )}
              </div>
            </div>
          )
        })}

        {/* 若有不在当前 workflow 定义里的其他历史状态 Issue，兜底展示 */}
        {issuesByState.unknownStateIssues.length > 0 && (
          <div className="project-board-column other-states-column">
            <div className="column-header">
              <div className="column-header-left">
                <h3 className="column-title">其他状态</h3>
              </div>
              <span className="column-count">
                {issuesByState.unknownStateIssues.length}
              </span>
            </div>
            <div className="column-body">
              {issuesByState.unknownStateIssues.map((item) => (
                <IssueCard
                  key={item.issue.id}
                  item={item}
                  onClick={() => onSelectIssue(item.issue.id)}
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
