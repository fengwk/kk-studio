import type { FormEventHandler } from 'react'
import { buildResourceSubmitPlan } from '@/features/catalog/ai-resource-editor-submit-plans'
import { validateResourceDraft } from '@/features/catalog/ai-resource-form-validation'
import { useAiConsoleResourceMutations } from '@/features/catalog/useAiConsoleResourceMutations'
import {
  useAiConsoleResourceQueries,
  type AiConsoleResourceQueryEnabled,
} from '@/features/catalog/useAiConsoleResourceQueries'
import { useResourceDeleteConfirm, useResourceFormErrors } from '@/features/catalog/ai-resource-editor-state'
import { useDraftResourceSync, useResourceMutationError } from '@/features/catalog/useResourceDraftSync'
import { useResourceEditorNavigation } from '@/features/catalog/useResourceEditorNavigation'
import type { CapabilityDirectory } from '@/features/catalog/CapabilitySelectionPanels'
import type { AgentDraft, ModelDraft, ModelGroupDraft, ProviderDraft } from '@/features/catalog/ai-console-types'

/**
 * 能力目录的读取状态直接取自各自的查询：失败可重试，成功即可信目录，
 * 其余（含未启用）都是尚未读到，避免把未知当成「目录为空」。
 */
function capabilityDirectory(query: {
  isError: boolean
  isSuccess: boolean
  refetch: () => unknown
}): CapabilityDirectory {
  return {
    status: query.isError ? 'error' : query.isSuccess ? 'ready' : 'loading',
    retry: () => {
      void query.refetch()
    },
  }
}

/**
 * Catalog 资源编辑器控制器：资源查询、编辑器草稿与打开计划、保存校验、
 * 删除确认和 Dialog props 都收敛在这里；页面控制器只负责搜索与面板装配。
 */
export function useAiConsoleResourceController(
  enabled: AiConsoleResourceQueryEnabled,
) {
  const { queries, resources } = useAiConsoleResourceQueries(enabled)
  const { providers, models, agents, toolCatalog, skills } = resources
  // 三份目录各自独立，任一失败不影响其他能力列表的可用性判断。
  const capabilityDirectories = {
    tools: capabilityDirectory(queries.tools),
    skills: capabilityDirectory(queries.skills),
    subagents: capabilityDirectory(queries.agents),
  }

  const navigation = useResourceEditorNavigation()
  const { resourceModal, drafts, closeResourceModal } = navigation
  const errors = useResourceFormErrors(resourceModal)
  const deleteConfirmState = useResourceDeleteConfirm()

  const mutations = useAiConsoleResourceMutations({
    onResourceSaved: closeResourceModal,
    onDeleteCompleted: deleteConfirmState.closeDeleteConfirm,
    onDeleteError: deleteConfirmState.failDelete,
  })

  useDraftResourceSync({
    modal: resourceModal,
    providers,
    models,
    setModelDraft: drafts.setModelDraft,
    setAgentDraft: drafts.setAgentDraft,
  })

  useResourceMutationError({
    modal: resourceModal,
    mutationError: mutations.resourceMutationError,
    showFailure: errors.showFailure,
  })

  function submitPlan(plan: ReturnType<typeof buildResourceSubmitPlan>) {
    if (plan.kind === 'provider') {
      if (plan.mode === 'edit') {
        mutations.updateProvider(plan.name, plan.data)
      } else {
        mutations.createProvider(plan.data)
      }
      return
    }
    if (plan.kind === 'model') {
      if (plan.mode === 'edit') {
        mutations.updateModel(plan.name, plan.data)
      } else {
        mutations.createModel(plan.data)
      }
      return
    }
    if (plan.mode === 'edit') {
      mutations.updateAgent(plan.name, plan.data)
    } else {
      mutations.createAgent(plan.data)
    }
  }

  const submitResource: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!resourceModal) {
      return
    }
    const validation = validateResourceDraft(resourceModal, drafts.drafts())
    if (!validation.ok) {
      errors.setFormError(validation.message)
      errors.setFieldErrors(validation.fields)
      return
    }
    errors.clear()
    submitPlan(buildResourceSubmitPlan(resourceModal, drafts.drafts()))
  }

  /** 编辑草稿时清空上一条校验错误：错误只在提交后成立。 */
  function draftChangeHandler<T>(apply: (draft: T) => void) {
    return (draft: T) => {
      errors.clear()
      apply(draft)
    }
  }

  function requestDelete(kind: 'provider' | 'model' | 'agent', name: string, expectedVersion: string) {
    const actions = {
      provider: {
        titleKey: 'ai.catalog.deleteProviderTitle',
        descriptionKey: 'ai.catalog.deleteProviderDescription',
        onConfirm: () => mutations.deleteProvider(name, expectedVersion),
      },
      model: {
        titleKey: 'ai.catalog.deleteModelTitle',
        descriptionKey: 'ai.catalog.deleteModelDescription',
        onConfirm: () => mutations.deleteModel(name, expectedVersion),
      },
      agent: {
        titleKey: 'ai.catalog.deleteAgentTitle',
        descriptionKey: 'ai.catalog.deleteAgentDescription',
        onConfirm: () => mutations.deleteAgent(name, expectedVersion),
      },
    } as const
    deleteConfirmState.requestDelete({ ...actions[kind], name })
  }

  return {
    queries,
    resources,
    resourceMutationError: mutations.resourceMutationError,
    deletePending: {
      provider: mutations.providerDeletePending,
      model: mutations.modelDeletePending,
      agent: mutations.agentDeletePending,
    },
    resourceEditorModal: {
      modal: resourceModal,
      providers,
      models,
      agents,
      toolCatalog,
      skills,
      capabilityDirectories,
      providerDraft: drafts.providerDraft,
      modelDraft: drafts.modelDraft,
      modelGroupDraft: drafts.modelGroupDraft,
      agentDraft: drafts.agentDraft,
      pending: mutations.resourceEditorPending,
      formError: errors.formError,
      fieldErrors: errors.fieldErrors,
      onClose: closeResourceModal,
      onProviderDraftChange: draftChangeHandler<ProviderDraft>(drafts.setProviderDraft),
      onModelDraftChange: draftChangeHandler<ModelDraft>(drafts.setModelDraft),
      onModelGroupDraftChange: draftChangeHandler<ModelGroupDraft>(drafts.setModelGroupDraft),
      /** 仅创建态切换 Tab；两份草稿都保留在 state 中。 */
      onModelCreateKindChange: navigation.selectModelCreateKind,
      onAgentDraftChange: draftChangeHandler<AgentDraft>(drafts.setAgentDraft),
      onSubmit: submitResource,
    },
    deleteConfirmModal: {
      modal: deleteConfirmState.deleteConfirm,
      pending: mutations.deleteConfirmPending,
      onClose: deleteConfirmState.closeDeleteConfirm,
    },
    editorActions: {
      createProvider: navigation.createProvider,
      editProvider: (providerName: string) => navigation.editProvider(providers, providerName),
      createModel: () => navigation.createModel(providers),
      editModel: (modelName: string) => navigation.editModel(models, modelName),
      createAgent: () => navigation.createAgent(models),
      editAgent: (agentName: string) => navigation.editAgent(agents, models, agentName),
    },
    deleteResource: {
      provider: (name: string, expectedVersion: string) =>
        requestDelete('provider', name, expectedVersion),
      model: (name: string, expectedVersion: string) => requestDelete('model', name, expectedVersion),
      agent: (name: string, expectedVersion: string) => requestDelete('agent', name, expectedVersion),
    },
  }
}
