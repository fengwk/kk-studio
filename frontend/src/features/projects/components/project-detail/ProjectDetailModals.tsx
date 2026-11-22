import { CreateIssueModal } from '../CreateIssueModal'
import { DeleteProjectModal } from '../DeleteProjectModal'
import { EditProjectModal } from '../EditProjectModal'
import type { IssueDetailIntent } from '../../types'
import { IssueDetailModal } from '../IssueDetailModal'
import type { ProjectsApi } from '../../projects-api'
import type { ProjectDTO, ProjectSnapshotDTO, ProjectWorkflowDTO } from '@/shared/api/generated/contracts'

interface ProjectDetailModalsProps {
  projectId: string
  project: ProjectDTO
  workflow: ProjectWorkflowDTO
  snapshot: ProjectSnapshotDTO
  snapshotError: unknown
  api: ProjectsApi
  isEditOpen: boolean
  isDeleteOpen: boolean
  isCreateIssueOpen: boolean
  onCloseEdit: () => void
  onCloseDelete: () => void
  onCloseCreateIssue: () => void
  onDeleted: () => void
  onProjectUpdated: () => void
  issueDetail: {
    isOpen: boolean
    issueId: string | null
    /** 详情打开意图：卡片“阻塞”要求打开后直接展开阻塞原因表单。 */
    intent: IssueDetailIntent | null
    onClose: () => void
    onOpenThread: (threadId: string) => void
  }
}

/**
 * 看板挂载的四个弹窗：编辑项目、删除项目、新建 Issue 与 Issue 详情。
 *
 * <p>Issue 详情按 issue id 重建，切换 issue 时不复用上一个的草稿状态。
 */
export function ProjectDetailModals({
  projectId,
  project,
  workflow,
  snapshot,
  snapshotError,
  api,
  isEditOpen,
  isDeleteOpen,
  isCreateIssueOpen,
  onCloseEdit,
  onCloseDelete,
  onCloseCreateIssue,
  onDeleted,
  onProjectUpdated,
  issueDetail,
}: ProjectDetailModalsProps) {
  return (
    <>
      <EditProjectModal
        isOpen={isEditOpen}
        initialTab="workflow"
        project={project}
        snapshot={snapshot}
        snapshotError={snapshotError}
        onClose={onCloseEdit}
        onSuccess={onProjectUpdated}
        api={api}
      />

      <DeleteProjectModal
        isOpen={isDeleteOpen}
        project={project}
        onClose={onCloseDelete}
        onSuccess={onDeleted}
        api={api}
      />

      <CreateIssueModal
        isOpen={isCreateIssueOpen}
        projectId={projectId}
        workflow={workflow}
        onClose={onCloseCreateIssue}
        onSuccess={onProjectUpdated}
        api={api}
      />

      <IssueDetailModal
        key={issueDetail.issueId ?? 'none'}
        isOpen={issueDetail.isOpen}
        projectId={projectId}
        issueId={issueDetail.issueId}
        workflow={workflow}
        initialAction={issueDetail.intent}
        onClose={issueDetail.onClose}
        onUpdated={onProjectUpdated}
        onOpenThread={issueDetail.onOpenThread}
        api={api}
      />
    </>
  )
}
