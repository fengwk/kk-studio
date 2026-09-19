import { useEffect, useState, type FormEventHandler } from 'react'
import { buildResourceSubmitPlan } from '@/features/ai/catalog/ai-resource-editor-submit-plans'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import {
  validateResourceDraft,
  type ResourceFieldKey,
} from '@/features/ai/catalog/ai-resource-form-validation'
import { useAiConsoleResourceEditorState } from '@/features/ai/catalog/useAiConsoleResourceEditorState'
import { useAiConsoleResourceMutations } from '@/features/ai/catalog/useAiConsoleResourceMutations'
import {
  useAiConsoleResourceQueries,
  type AiConsoleResourceQueryEnabled,
} from '@/features/ai/catalog/useAiConsoleResourceQueries'
import { useI18n } from '@/shared/i18n'

export function useAiConsoleResourceController(
  enabled: AiConsoleResourceQueryEnabled,
) {
  const { t, locale } = useI18n()
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const [formError, setFormError] = useState<string>('')
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<ResourceFieldKey, string>>>({})
  const {
    providersQuery,
    modelsQuery,
    agentsQuery,
    toolsQuery,
    skillsQuery,
    environmentsQuery,
    providers,
    models,
    agents,
    toolCatalog,
    skills,
    environments,
  } = useAiConsoleResourceQueries(enabled)
  const editorState = useAiConsoleResourceEditorState({ providers, models, agents })

  const closeDeleteConfirm = () => setDeleteConfirm(null)
  const mutations = useAiConsoleResourceMutations({
    onResourceSaved: editorState.closeResourceModal,
    onDeleteCompleted: closeDeleteConfirm,
  })

  function deleteProvider(providerName: string, expectedVersion: string) {
    setDeleteConfirm({
      title: t('ai.catalog.deleteProviderTitle'),
      description: t('ai.catalog.deleteProviderDescription', { name: providerName }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () => mutations.deleteProvider(providerName, expectedVersion),
    })
  }

  function deleteModel(
    providerName: string,
    modelName: string,
    expectedVersion: string,
  ) {
    setDeleteConfirm({
      title: t('ai.catalog.deleteModelTitle'),
      description: t('ai.catalog.deleteModelDescription', {
        name: modelName.includes('/') ? modelName : `${providerName}/${modelName}`,
      }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () => mutations.deleteModel(providerName, modelName, expectedVersion),
    })
  }

  function deleteAgent(agentName: string, expectedVersion: string) {
    setDeleteConfirm({
      title: t('ai.catalog.deleteAgentTitle'),
      description: t('ai.catalog.deleteAgentDescription', { name: agentName }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () => mutations.deleteAgent(agentName, expectedVersion),
    })
  }

  // 打开/关闭编辑器时清空校验状态
  useEffect(() => {
    setFormError('')
    setFieldErrors({})
  }, [editorState.resourceModal])

  // API 错误：转成用户可读文案，只显示在模态内
  useEffect(() => {
    if (!editorState.resourceModal || !mutations.resourceMutationError) {
      return
    }
    const message = toUserFacingErrorMessage(mutations.resourceMutationError)
    setFormError(message)
    setFieldErrors((current) =>
      Object.keys(current).length > 0 ? current : { general: message },
    )
  }, [editorState.resourceModal, mutations.resourceMutationError, locale])

  const submitResource: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!editorState.resourceModal) {
      return
    }

    const drafts = {
      providerDraft: editorState.providerDraft,
      modelDraft: editorState.modelDraft,
      agentDraft: editorState.agentDraft,
    }
    const validation = validateResourceDraft(editorState.resourceModal, drafts)
    if (!validation.ok) {
      setFormError(validation.message)
      setFieldErrors(validation.fields)
      const form = event.currentTarget as HTMLFormElement
      const body = form.querySelector('.modal-body')
      body?.scrollTo({ top: 0, behavior: 'smooth' })
      // 优先滚到首个标红字段，便于看见「思考强度」等具体项
      window.requestAnimationFrame(() => {
        const firstError =
          form.querySelector('.form-group.is-error') ||
          form.querySelector('.capability-picker.is-error') ||
          form.querySelector('.field-error')
        firstError?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      })
      return
    }
    setFormError('')
    setFieldErrors({})

    const plan = buildResourceSubmitPlan(editorState.resourceModal, drafts)

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
        mutations.updateModel(plan.providerName, plan.name, plan.data)
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

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    toolsQuery,
    skillsQuery,
    environmentsQuery,
    providers,
    models,
    agents,
    toolCatalog,
    skills,
    environments,
    resourceMutationError: mutations.resourceMutationError,
    providerDeletePending: mutations.providerDeletePending,
    modelDeletePending: mutations.modelDeletePending,
    agentDeletePending: mutations.agentDeletePending,
    resourceEditorModal: {
      modal: editorState.resourceModal,
      providers,
      models,
      agents,
      toolCatalog,
      skills,
      skillsLoading: skillsQuery.isLoading,
      skillsError: skillsQuery.error,
      providerDraft: editorState.providerDraft,
      modelDraft: editorState.modelDraft,
      agentDraft: editorState.agentDraft,
      pending: mutations.resourceEditorPending,
      formError,
      fieldErrors,
      onClose: editorState.closeResourceModal,
      onProviderDraftChange: (draft: typeof editorState.providerDraft) => {
        setFormError('')
        setFieldErrors({})
        editorState.onProviderDraftChange(draft)
      },
      onModelDraftChange: (draft: typeof editorState.modelDraft) => {
        setFormError('')
        setFieldErrors({})
        editorState.onModelDraftChange(draft)
      },
      onAgentDraftChange: (draft: typeof editorState.agentDraft) => {
        setFormError('')
        setFieldErrors({})
        editorState.onAgentDraftChange(draft)
      },
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
