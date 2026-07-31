import { useState, type FormEventHandler } from 'react'
import { useQuery } from '@tanstack/react-query'
import type {
  ComfyuiWorkflowDraft,
} from '@/features/comfyui/comfyui-types'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'
import {
  emptyComfyuiWorkflowDraft,
  errorMessage,
  validateComfyuiWorkflowDraft,
  workflowToDraft,
} from '@/features/comfyui/comfyui-utils'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import { comfyuiService } from '@/shared/api/comfyui-service'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

type ComfyuiEditorModal =
  | { mode: 'create'; workflow: null }
  | { mode: 'edit'; workflow: ComfyuiWorkflowApiDTO }

export function useComfyuiController() {
  const { t } = useI18n()
  const [editorModal, setEditorModal] = useState<ComfyuiEditorModal | null>(null)
  const [draft, setDraft] = useState<ComfyuiWorkflowDraft>(emptyComfyuiWorkflowDraft)
  const [editorError, setEditorError] = useState<string | null>(null)
  const [deleteWorkflow, setDeleteWorkflow] = useState<ComfyuiWorkflowApiDTO | null>(null)
  const [runWorkflow, setRunWorkflow] = useState<ComfyuiWorkflowApiDTO | null>(null)

  const workflowsQuery = useQuery({
    queryKey: queryKeys.comfyui.workflows,
    queryFn: () => comfyuiService.listWorkflows(),
  })

  const closeEditor = () => {
    setEditorModal(null)
    setEditorError(null)
  }
  const createMutation = useInvalidateMutation({
    mutationFn: (data: ReturnType<typeof validateComfyuiWorkflowDraft>) => comfyuiService.createWorkflow(data),
    invalidateQueryKeys: [queryKeys.comfyui.workflows],
    onSuccess: closeEditor,
  })
  const updateMutation = useInvalidateMutation({
    mutationFn: ({ workflow, data }: { workflow: ComfyuiWorkflowApiDTO; data: ReturnType<typeof validateComfyuiWorkflowDraft> }) =>
      comfyuiService.updateWorkflow(workflow.id, data),
    invalidateQueryKeys: [queryKeys.comfyui.workflows],
    onSuccess: closeEditor,
  })
  const deleteMutation = useInvalidateMutation({
    mutationFn: (workflow: ComfyuiWorkflowApiDTO) => comfyuiService.deleteWorkflow(workflow.id),
    invalidateQueryKeys: [queryKeys.comfyui.workflows],
    onSuccess: () => setDeleteWorkflow(null),
  })

  const submitEditor: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!editorModal) {
      return
    }
    setEditorError(null)
    try {
      const data = validateComfyuiWorkflowDraft(draft)
      if (editorModal.mode === 'create') {
        createMutation.mutate(data)
      } else {
        updateMutation.mutate({ workflow: editorModal.workflow, data })
      }
    } catch (error) {
      setEditorError(errorMessage(error))
    }
  }

  function openCreate() {
    setDraft({ ...emptyComfyuiWorkflowDraft })
    setEditorError(null)
    setEditorModal({ mode: 'create', workflow: null })
  }

  function openEdit(workflow: ComfyuiWorkflowApiDTO) {
    setDraft(workflowToDraft(workflow))
    setEditorError(null)
    setEditorModal({ mode: 'edit', workflow })
  }

  function requestDelete(workflow: ComfyuiWorkflowApiDTO) {
    setDeleteWorkflow(workflow)
  }

  const deleteConfirm: ConfirmModalState | null = deleteWorkflow
    ? {
      title: t('comfyui.confirm.deleteTitle'),
      description: t('comfyui.confirm.deleteDescription', {
        name: deleteWorkflow.name,
        apiName: deleteWorkflow.apiName,
      }),
      confirmLabel: t('comfyui.confirm.delete'),
      tone: 'danger',
      onConfirm: () => deleteMutation.mutate(deleteWorkflow),
    }
    : null

  const editorMutationError = createMutation.error || updateMutation.error
  return {
    workflowsQuery,
    workflows: workflowsQuery.data?.results ?? [],
    mutationError: editorMutationError || deleteMutation.error,
    deletePending: deleteMutation.isPending,
    openCreate,
    openEdit,
    requestDelete,
    openRun: setRunWorkflow,
    editorModalProps: {
      modal: editorModal,
      draft,
      pending: createMutation.isPending || updateMutation.isPending,
      error: editorError || (editorMutationError ? errorMessage(editorMutationError) : null),
      onClose: closeEditor,
      onDraftChange: setDraft,
      onSubmit: submitEditor,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: deleteMutation.isPending,
      onClose: () => setDeleteWorkflow(null),
    },
    runModalProps: {
      workflow: runWorkflow,
      onClose: () => setRunWorkflow(null),
    },
  }
}
