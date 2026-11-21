import { useMemo } from 'react'
import { useI18n } from '@/shared/i18n'
import { IssueCard } from './IssueCard'
import type { ProjectIssueSnapshotDTO, ProjectWorkflowDTO } from '@/shared/api/generated/contracts'

export interface IssueBoardProps {
  workflow?: ProjectWorkflowDTO
  issues: ProjectIssueSnapshotDTO[]
  /** 搜索由页面头部持有；看板只按结果渲染列与卡片。 */
  searchQuery: string
  onSelectIssue: (issueId: string) => void
  onTransitionIssue: (issueId: string, expectedVersion: string, toState: string) => void
  onBlockIssue: (issueId: string, expectedVersion: string) => void
  onRecoverIssue: (issueId: string, expectedVersion: string) => void
  onReopenIssue: (issueId: string, expectedVersion: string) => void
}

export function IssueBoard({
  workflow,
  issues,
  searchQuery,
  onSelectIssue,
  onTransitionIssue,
  onBlockIssue,
  onRecoverIssue,
  onReopenIssue,
}: IssueBoardProps) {
  const { t } = useI18n()
  const states = useMemo(
    () =>
      workflow?.states ?? [
        { state: 'INIT', name: t('projects.state.init'), next: ['DONE'] },
        { state: 'BLOCKED', name: t('projects.state.blocked') },
        { state: 'DONE', name: t('projects.state.done') },
      ],
    [workflow, t],
  )

  // 阶段编码到名称的映射：列头、卡片与搜索都以名称为主，编码只在提示里出现。
  const stateNames = useMemo(
    () => new Map(states.map((st) => [st.state, st.name])),
    [states],
  )

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
      if (!q) {
        return true
      }
      const stageName = stateNames.get(item.issue.state) ?? ''
      return (
        item.issue.title.toLowerCase().includes(q) ||
        item.issue.number.includes(q) ||
        stageName.toLowerCase().includes(q) ||
        item.issue.state.toLowerCase().includes(q)
      )
    })
  }, [issues, searchQuery, stateNames])

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
      {/* 状态自然 token 动态看板列 */}
      <div className="project-board-columns">
        {states.map((st) => {
          const colIssues = issuesByState.map.get(st.state) ?? []
          return (
            <div key={st.state} className="project-board-column" data-state={st.state}>
              <div className="column-header">
                {/* 内部状态码只在提示里保留，列头以阶段名称为主。 */}
                <h3 className="column-title" title={st.state}>{st.name}</h3>
                <span className="column-count">{colIssues.length}</span>
              </div>

              <div className="column-body">
                {colIssues.length === 0 ? (
                  <div className="column-empty">
                    <span>{t('projects.board.emptyColumn')}</span>
                  </div>
                ) : (
                  colIssues.map((item) => (
                    <IssueCard
                      key={item.issue.id}
                      item={item}
                      stateNames={stateNames}
                      availableNextStates={stateNextMap.get(item.issue.state) ?? []}
                      onClick={() => onSelectIssue(item.issue.id)}
                      onTransition={(toState) =>
                        onTransitionIssue(item.issue.id, item.issue.version, toState)
                      }
                      onBlock={() => onBlockIssue(item.issue.id, item.issue.version)}
                      onRecover={() => onRecoverIssue(item.issue.id, item.issue.version)}
                      onReopen={() => onReopenIssue(item.issue.id, item.issue.version)}
                    />
                  ))
                )}
              </div>
            </div>
          )
        })}

        {/* 若有不在当前 workflow 定义里的其他历史状态 Issue，兜底展示 */}
        {issuesByState.unknownStateIssues.length > 0 && (
          <div className="project-board-column">
            <div className="column-header">
              <h3 className="column-title">{t('projects.board.otherStates')}</h3>
              <span className="column-count">
                {issuesByState.unknownStateIssues.length}
              </span>
            </div>
            <div className="column-body">
              {issuesByState.unknownStateIssues.map((item) => (
                <IssueCard
                  key={item.issue.id}
                  item={item}
                  stateNames={stateNames}
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
