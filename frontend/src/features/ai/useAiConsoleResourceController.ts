import { useState, type FormEventHandler } from 'react'
import { buildResourceSubmitPlan } from '@/features/ai/ai-resource-editor-submit-plans'
import type { ConfirmModalState } from '@/features/ai/ai-console-types'
import type { AgentResourceId } from '@/shared/api/contracts'
import { useAiConsoleResourceEditorState } from '@/features/ai/useAiConsoleResourceEditorState'
import { useAiConsoleResourceMutations } from '@/features/ai/useAiConsoleResourceMutations'
import { useAiConsoleResourceQueries } from '@/features/ai/useAiConsoleResourceQueries'

export function useAiConsoleResourceController(workspaceId: string) {
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const { providersQuery, modelsQuery, agentsQuery, providers, models, agents } = useAiConsoleResourceQueries(workspaceId)
  const editorState = useAiConsoleResourceEditorState({ providers, models, agents })
  const closeDeleteConfirm = () => setDeleteConfirm(null)
  const mutations = useAiConsoleResourceMutations({
    workspaceId,
    onResourceSaved: editorState.closeResourceModal,
    onDeleteCompleted: closeDeleteConfirm,
  })

  function deleteProvider(providerName: string, providerId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Provider',
      description: `将删除 Provider ${providerName}。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => mutations.deleteProvider(providerId),
    })
  }

  function deleteModel(providerName: string, modelName: string, modelId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Model',
      description: `将删除 Model ${providerName}/${modelName}。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => mutations.deleteModel(modelId),
    })
  }

  function deleteAgent(agentName: string, agentId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Agent',
      description: `将删除 Agent ${agentName}。已有会话会保留，但不能再用该 Agent 新建运行。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => mutations.deleteAgent(agentId),
    })
  }

  const submitResource: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!editorState.resourceModal) {
      return
    }

    const plan = buildResourceSubmitPlan(editorState.resourceModal, {
      providerDraft: editorState.providerDraft,
      modelDraft: editorState.modelDraft,
      agentDraft: editorState.agentDraft,
    })

    if (plan.kind === 'provider') {
      if (plan.mode === 'edit') {
        mutations.updateProvider(plan.id, plan.data)
      } else {
        mutations.createProvider(plan.data)
      }
      return
    }

    if (plan.kind === 'model') {
      if (plan.mode === 'edit') {
        mutations.updateModel(plan.id, plan.data)
      } else {
        mutations.createModel(plan.data)
      }
      return
    }

    if (plan.mode === 'edit') {
      mutations.updateAgent(plan.id, plan.data)
    } else {
      mutations.createAgent(plan.data)
    }
  }

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    providers,
    models,
    agents,
    resourceMutationError: mutations.resourceMutationError,
    providerDeletePending: mutations.providerDeletePending,
    modelDeletePending: mutations.modelDeletePending,
    agentDeletePending: mutations.agentDeletePending,
    resourceEditorModal: {
      modal: editorState.resourceModal,
      providers,
      models,
      providerDraft: editorState.providerDraft,
      modelDraft: editorState.modelDraft,
      agentDraft: editorState.agentDraft,
      pending: mutations.resourceEditorPending,
      onClose: editorState.closeResourceModal,
      onProviderDraftChange: editorState.onProviderDraftChange,
      onModelDraftChange: editorState.onModelDraftChange,
      onAgentDraftChange: editorState.onAgentDraftChange,
      onSubmit: submitResource,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: mutations.deleteConfirmPending,
      onClose: closeDeleteConfirm,
    },
    openCreateProvider: editorState.openCreateProvider,
    openEditProvider: editorState.openEditProvider,
    deleteProvider,
    openCreateModel: editorState.openCreateModel,
    openEditModel: editorState.openEditModel,
    deleteModel,
    openCreateAgent: editorState.openCreateAgent,
    openEditAgent: editorState.openEditAgent,
    deleteAgent,
  }
}
