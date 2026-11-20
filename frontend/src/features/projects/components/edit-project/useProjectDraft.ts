import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { environmentService } from '@/features/environment/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { ProjectDTO, ProjectSnapshotDTO } from '@/shared/api/generated/contracts'
import type { ProjectsApi } from '../../projects-api'
import { useAgentCatalog } from '../../useAgentCatalog'
import { createDraftState, hasActiveRun, toDraftStates, type WorkflowDraftState } from '../../workflow-draft'

export type EditProjectTab = 'basic' | 'workflow'

interface UseProjectDraftInput {
  project: ProjectDTO
  initialTab: EditProjectTab
  snapshot: ProjectSnapshotDTO | undefined
  snapshotError: unknown
  api: ProjectsApi
}

/**
 * 项目编辑草稿与约束：打开时冻结的权威快照、三个子表单的输入、工作流草稿与可编辑性判定。
 *
 * <p>推送只更新查询缓存，不改写这里冻结的 CAS 基线与草稿；工作流在引用未就绪、项目已归档或
 * 存在活跃 Run 时锁定，并给出对应原因。
 */
export function useProjectDraft({ project, initialTab, snapshot, snapshotError, api }: UseProjectDraftInput) {
  const { t } = useI18n()
  const [activeTab, setActiveTab] = useState<EditProjectTab>(initialTab)
  const [frozenVersion, setFrozenVersion] = useState(project.version)
  const [title, setTitle] = useState(project.title)
  const [description, setDescription] = useState(project.description)
  const [yoloEnabled, setYoloEnabled] = useState(project.yoloEnabled)
  const [draftStates, setDraftStates] = useState<WorkflowDraftState[]>(() => toDraftStates(project.workflow))
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  const constraintsQuery = useQuery({
    queryKey: queryKeys.projects.snapshot(project.id),
    queryFn: () => api.getProjectSnapshot(project.id),
    enabled: !snapshot && activeTab === 'workflow',
  })
  const constraints = snapshot ?? constraintsQuery.data
  const constraintsError = snapshotError ?? constraintsQuery.error
  const constraintsReady = Boolean(constraints) && !constraintsError
  const referencedStates = useMemo(
    () => new Set(constraints?.referencedStateCodes ?? []),
    [constraints?.referencedStateCodes],
  )
  const activeRun = hasActiveRun(constraints?.issues)
  const workflowLockedReason = !constraintsReady
    ? constraintsError instanceof Error
      ? constraintsError.message
      : t('projects.edit.referencesLoading')
    : (constraints?.project ?? project).archivedAt
      ? t('projects.edit.lockedArchived')
      : activeRun
        ? t('projects.edit.lockedActiveRun')
        : null

  const agentCatalog = useAgentCatalog()
  const missingCatalogAgents = useMemo(() => {
    // 目录读取失败时目录内容未知：既不能判定已保存 Agent 不可用，也不能把它们列为缺失。
    if (!agentCatalog.ready) {
      return []
    }
    const catalogNames = new Set(agentCatalog.agents.map((agent) => agent.name))
    const assigned = new Set(
      draftStates.map((state) => state.agent).filter((name): name is string => Boolean(name)),
    )
    return Array.from(assigned)
      .filter((name) => !catalogNames.has(name))
      .sort((left, right) => left.localeCompare(right))
  }, [agentCatalog.ready, agentCatalog.agents, draftStates])

  const { data: environments = [] } = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  return {
    activeTab,
    setActiveTab,
    frozenVersion,
    setFrozenVersion,
    title,
    setTitle,
    description,
    setDescription,
    yoloEnabled,
    setYoloEnabled,
    draftStates,
    setDraftStates,
    selectedKey,
    setSelectedKey,
    constraintsQuery,
    constraintsError,
    constraintsReady,
    referencedStates,
    workflowLockedReason,
    agentCatalog,
    missingCatalogAgents,
    environments,
  }
}

type ProjectDraft = ReturnType<typeof useProjectDraft>

/**
 * 工作流草稿的编辑操作：改码同步改写转移边、增删改排与勾选后继。
 *
 * <p>改码与删除受约束保护：引用未就绪或阶段被 Issue / 阻塞恢复点引用时不生效。
 */
export function useWorkflowDraftEditing({
  draftStates,
  setDraftStates,
  setSelectedKey,
  constraintsReady,
  referencedStates,
  onEdited,
}: Pick<ProjectDraft, 'draftStates' | 'setDraftStates' | 'setSelectedKey' | 'constraintsReady' | 'referencedStates'> & {
  /** 第一次改动后清除该 scope 的成功提示。 */
  onEdited: () => void
}) {
  const updateState = (key: string, patch: Partial<WorkflowDraftState>) => {
    setDraftStates((prev) =>
      prev.map((state) => (state.key === key ? { ...state, ...patch } : state)),
    )
    onEdited()
  }

  /** 改码是重命名：同步改写所有引用该编码的转移边，保持图自洽。 */
  const renameState = (key: string, rawCode: string) => {
    const nextCode = rawCode.toUpperCase().trim()
    setDraftStates((prev) => {
      const target = prev.find((state) => state.key === key)
      if (!constraintsReady || referencedStates.has(target?.state ?? '')) {
        return prev
      }
      const oldCode = target?.state ?? ''
      return prev.map((state) => {
        if (state.key === key) {
          return { ...state, state: nextCode }
        }
        if (oldCode && oldCode !== nextCode && state.next.includes(oldCode)) {
          return { ...state, next: state.next.map((code) => (code === oldCode ? nextCode : code)) }
        }
        return state
      })
    })
    onEdited()
  }

  const toggleNext = (key: string, target: string, checked: boolean) => {
    setDraftStates((prev) =>
      prev.map((state) => {
        if (state.key !== key) return state
        const next = checked
          ? [...state.next, target]
          : state.next.filter((code) => code !== target)
        return { ...state, next }
      }),
    )
    onEdited()
  }

  const moveState = (index: number, delta: number) => {
    setDraftStates((prev) => {
      const target = index + delta
      if (target < 0 || target >= prev.length) {
        return prev
      }
      const next = [...prev]
      const [moved] = next.splice(index, 1)
      next.splice(target, 0, moved)
      return next
    })
    onEdited()
  }

  const addState = () => {
    const created = createDraftState()
    setDraftStates((prev) => [...prev, created])
    setSelectedKey(created.key)
    onEdited()
  }

  const removeState = (key: string) => {
    const targetState = draftStates.find((state) => state.key === key)
    if (!constraintsReady || referencedStates.has(targetState?.state ?? '')) {
      return
    }
    setDraftStates((prev) => prev.filter((state) => state.key !== key))
    setSelectedKey((current) => (current === key ? null : current))
    onEdited()
  }

  return { updateState, renameState, toggleNext, moveState, addState, removeState }
}
