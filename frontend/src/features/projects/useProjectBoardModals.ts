import { useEffect, useState } from 'react'
import { useLocation } from 'react-router'
import { useI18n } from '@/shared/i18n'
import type { ProjectDTO } from '@/shared/api/generated/contracts'
import type { ProjectsApi } from './projects-api'

/**
 * 看板上“当前打开的弹窗”与手动详情意图。
 *
 * <p>手动打开详情不进入 URL；路由或查询参数一变就收起，避免前进后退复活弹窗。
 */
export function useProjectBoardModals() {
  const location = useLocation()
  const [isManualDetailOpen, setIsManualDetailOpen] = useState(false)
  const [isEditProjectOpen, setIsEditProjectOpen] = useState(false)
  const [isDeleteProjectOpen, setIsDeleteProjectOpen] = useState(false)
  const [isCreateIssueOpen, setIsCreateIssueOpen] = useState(false)

  const dismissAll = () => setIsManualDetailOpen(false)

  // 路由或参数变化时收起手动打开的详情弹窗。
  useEffect(() => {
    setIsManualDetailOpen(false)
  }, [location.key])

  return {
    isManualDetailOpen,
    openManualDetail: () => setIsManualDetailOpen(true),
    dismissManualDetail: dismissAll,
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
