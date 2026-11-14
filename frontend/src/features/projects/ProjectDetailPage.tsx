import { AlertTriangle } from 'lucide-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { queryKeys } from '@/shared/lib/query-keys'
import { IssueBoard } from './components/IssueBoard'
import { ProjectBoardToolbar } from './components/project-detail/ProjectBoardToolbar'
import { ProjectDetailModals } from './components/project-detail/ProjectDetailModals'
import {
  ProjectAgentDock,
  ProjectDetailPlaceholder,
  ProjectDetailTitleBar,
} from './components/project-detail/ProjectDetailPanels'
import { useBoardIssueActions } from './useBoardIssueActions'
import { useProjectArchive, useProjectBoardModals } from './useProjectBoardModals'
import { useProjectBoardRoute } from './useProjectBoardRoute'
import { useProjectThreadGate } from './useProjectThreadGate'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import './projects.css'

export interface ProjectDetailPageProps {
  projectId: string
  onBack?: () => void
  api?: ProjectsApi
}

/**
 * 项目看板：项目快照、状态列 Issue 卡片、受控 Agent 面板与四种弹窗。
 *
 * <p>路由状态、弹窗开关、归档写操作、看板 Issue 写操作与线程门禁各自在自己的 hook 里；
 * 页面只负责把快照投影到看板并编排这些部件。
 */
export function ProjectDetailPage({ projectId, onBack, api = projectsApi }: ProjectDetailPageProps) {
  const queryClient = useQueryClient()
  const modals = useProjectBoardModals()
  const route = useProjectBoardRoute({ onDismissManualDetail: modals.dismissManualDetail })
  const [searchQuery, setSearchQuery] = useState('')

  const {
    data: snapshot,
    isLoading,
    error: queryError,
    refetch,
  } = useQuery({
    queryKey: queryKeys.projects.snapshot(projectId),
    queryFn: () => api.getProjectSnapshot(projectId),
  })

  const invalidateSnapshot = async () => {
    await queryClient.invalidateQueries({
      queryKey: queryKeys.projects.snapshot(projectId),
      exact: true,
    })
  }

  const archive = useProjectArchive({ api, onUpdated: invalidateSnapshot })
  const boardActions = useBoardIssueActions({ projectId, api, onSelectIssue: route.selectIssue })
  const threadGate = useProjectThreadGate({
    projectId,
    issueId: route.issueId,
    threadId: route.threadId,
    api,
  })

  // 从线程视图关闭详情只退出弹窗，保留用户正在看的 issue；普通模式关闭则清掉 URL 参数。
  const closeIssueDetail = () => {
    if (route.threadId) {
      modals.dismissManualDetail()
      return
    }
    route.selectIssue(null)
  }

  if (!snapshot) {
    return (
      <ProjectDetailPlaceholder
        isLoading={isLoading}
        errorMessage={archive.error || (queryError instanceof Error ? queryError.message : null)}
        onBack={onBack}
        onRetry={() => void refetch()}
      />
    )
  }

  const project = snapshot.project
  const errorMessage =
    archive.error || boardActions.actionError || (queryError instanceof Error ? queryError.message : null)
  const isIssueDetailOpen = Boolean(route.issueId && (!route.threadId || modals.isManualDetailOpen))

  return (
    <div className="project-detail-layout">
      <header className="project-detail-header">
        <ProjectDetailTitleBar
          title={project.title}
          archived={Boolean(project.archivedAt)}
          yoloEnabled={project.yoloEnabled}
          onBack={onBack}
        />
        <ProjectBoardToolbar
          searchQuery={searchQuery}
          archived={Boolean(project.archivedAt)}
          onSearchChange={setSearchQuery}
          onCreateIssue={modals.openCreateIssue}
          onEditWorkflow={modals.openEditProject}
          onToggleArchive={() => void archive.toggle(project)}
          onDeleteProject={modals.openDeleteProject}
        />
      </header>

      {errorMessage && (
        <div className="form-error-banner" role="alert" style={{ margin: '12px 20px 0 20px' }}>
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{errorMessage}</span>
        </div>
      )}

      <div className="project-detail-body">
        <IssueBoard
          workflow={project.workflow}
          issues={snapshot.issues}
          searchQuery={searchQuery}
          onSelectIssue={route.selectIssue}
          onTransitionIssue={boardActions.transitionIssue}
          onBlockIssue={boardActions.openBlockDialog}
          onRecoverIssue={boardActions.recoverIssue}
          onReopenIssue={boardActions.reopenIssue}
        />

        {route.threadId && (
          <ProjectAgentDock
            threadId={route.threadId}
            issueTitle={threadGate.issueTitle}
            isLoading={threadGate.isLoading}
            isThreadValid={threadGate.isThreadValid}
            agentName={threadGate.agentName}
            agents={threadGate.agents}
            environments={threadGate.environments}
            onOpenIssueDetail={modals.openManualDetail}
            onClose={route.closeThread}
          />
        )}
      </div>

      <ProjectDetailModals
        projectId={projectId}
        project={project}
        workflow={project.workflow}
        snapshot={snapshot}
        snapshotError={queryError}
        api={api}
        isEditOpen={modals.isEditProjectOpen}
        isDeleteOpen={modals.isDeleteProjectOpen}
        isCreateIssueOpen={modals.isCreateIssueOpen}
        onCloseEdit={modals.closeEditProject}
        onCloseDelete={modals.closeDeleteProject}
        onCloseCreateIssue={modals.closeCreateIssue}
        onDeleted={() => onBack?.()}
        onProjectUpdated={() => void invalidateSnapshot()}
        issueDetail={{
          isOpen: isIssueDetailOpen,
          issueId: route.issueId,
          onClose: closeIssueDetail,
          onOpenThread: route.openThread,
        }}
      />
    </div>
  )
}
