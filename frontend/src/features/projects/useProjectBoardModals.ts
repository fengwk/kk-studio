import { useCallback, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import type { ProjectDTO } from '@/shared/api/generated/contracts'
import type { ProjectsApi } from './projects-api'

/**
 * 看板上“当前打开的弹窗”与手动详情意图。
 *
 * <p>手动打开详情不进入 URL；收起它的时机由 useProjectBoardRoute 在读路由时统一处理，
 * 避免前进后退复活弹窗。
 */
export function useProjectBoardModals() {
  const [isManualDetailOpen, setIsManualDetailOpen] = useState(false)
  const [isEditProjectOpen, setIsEditProjectOpen] = useState(false)
  const [isDeleteProjectOpen, setIsDeleteProjectOpen] = useState(false)
  const [isCreateIssueOpen, setIsCreateIssueOpen] = useState(false)

  // 路由 hook 的 action 与兜底 effect 都会调用它，保持引用稳定以免重复触发 effect。
  const dismissManualDetail = useCallback(() => setIsManualDetailOpen(false), [])

  return {
    isManualDetailOpen,
    openManualDetail: () => setIsManualDetailOpen(true),
    dismissManualDetail,
    isEditProjectOpen,
    openEditProject: () => setIsEditProjectOpen(true),
    closeEditProject: () => setIsEditProjectOpen(false),
    isDeleteProjectOpen,
    openDeleteProject: () => setIsDeleteProjectOpen(true),
    closeDeleteProject: () => setIsDeleteProjectOpen(false),
    isCreateIssueOpen,
    openCreateIssue: () => setIsCreateIssueOpen(true),
    closeCreateIssue: () => setIsCreateIssueOpen(false),
  }
}

interface UseProjectArchiveInput {
  api: ProjectsApi
  onUpdated: () => Promise<void>
}

/** 项目归档 / 取消归档：成功后重读快照，失败只提示并保留当前视图。 */
export function useProjectArchive({ api, onUpdated }: UseProjectArchiveInput) {
  const { t } = useI18n()
  const [error, setError] = useState<string | null>(null)

  const toggle = async (project: ProjectDTO) => {
    try {
      setError(null)
      if (project.archivedAt) {
        await api.unarchiveProject(project.id, { expectedVersion: project.version })
      } else {
        await api.archiveProject(project.id, { expectedVersion: project.version })
      }
      await onUpdated()
    } catch (err) {
      setError(err instanceof Error ? err.message : t('projects.detail.archiveFailed'))
    }
  }

  return { error, toggle }
}
