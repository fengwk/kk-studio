import { useDeferredValue, useMemo, useState } from 'react'
import { filterComfyuiWorkflows } from '@/features/ai/comfyui-utils'
import { useComfyuiController } from '@/features/ai/useComfyuiController'

/**
 * Isolated controller for the ComfyUI workflow page.
 *
 * Unlike `useAiConsoleController`, this hook never touches the legacy
 * `/api/agent/**` resource or session endpoints, so the page keeps working
 * after those endpoints disappear. It exposes only the ComfyUI workflow
 * state plus a local search box that filters workflows client-side.
 */
export function useComfyuiPageController() {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())
  const comfyuiController = useComfyuiController()

  const filteredWorkflows = useMemo(
    () => filterComfyuiWorkflows(comfyuiController.workflows, deferredSearch),
    [comfyuiController.workflows, deferredSearch],
  )

  const busy = comfyuiController.workflowsQuery.isLoading
  const error = comfyuiController.workflowsQuery.error ?? null
  const mutationError = comfyuiController.mutationError

  return {
    search,
    setSearch,
    busy,
    error,
    mutationError,
    comfyuiPanelProps: {
      workflows: filteredWorkflows,
      deletePending: comfyuiController.deletePending,
      onCreate: comfyuiController.openCreate,
      onRun: comfyuiController.openRun,
      onEdit: comfyuiController.openEdit,
      onDelete: comfyuiController.requestDelete,
    },
    comfyuiEditorModal: comfyuiController.editorModalProps,
    comfyuiDeleteConfirmModal: comfyuiController.deleteConfirmModal,
    comfyuiRunModal: comfyuiController.runModalProps,
  }
}
