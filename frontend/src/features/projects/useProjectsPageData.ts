import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ProjectsApi } from './projects-api'
import type { ProjectDTO } from '@/shared/api/generated/contracts'

interface UseProjectsPageDataInput {
  api: ProjectsApi
}

/**
 * 项目列表页的数据与动作：归档过滤、搜索、归档切换与列表失效。
 *
 * <p>操作错误与查询错误合并成一条展示文案，操作错误优先：它总是最近一次用户动作的结果。
 */
export function useProjectsPageData({ api }: UseProjectsPageDataInput) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [includeArchived, setIncludeArchived] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const {
    data: projects = [],
    isLoading,
    error: queryError,
    refetch,
  } = useQuery({
    queryKey: queryKeys.projects.list(includeArchived),
    queryFn: () => api.listProjects(includeArchived),
  })

  const filteredProjects = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) {
      return projects
    }
    return projects.filter(
      (p) =>
        p.title.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q),
    )
  }, [projects, searchQuery])

  const handleArchiveToggle = async (project: ProjectDTO) => {
    try {
      setActionError(null)
      if (project.archivedAt) {
        await api.unarchiveProject(project.id, { expectedVersion: project.version })
      } else {
        await api.archiveProject(project.id, { expectedVersion: project.version })
      }
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects.lists() })
    } catch (err) {
      setActionError(err instanceof Error ? err.message : t('projects.archiveFailed'))
    }
  }

  const retryLoad = async () => {
    setActionError(null)
    await refetch()
  }

  const invalidateProjectLists = async () => {
    setActionError(null)
    await queryClient.invalidateQueries({ queryKey: queryKeys.projects.lists() })
  }

  return {
    includeArchived,
    setIncludeArchived,
    searchQuery,
    setSearchQuery,
    projects,
    filteredProjects,
    isLoading,
    errorMessage: actionError || (queryError instanceof Error ? queryError.message : null),
    handleArchiveToggle,
    retryLoad,
    invalidateProjectLists,
  }
}
