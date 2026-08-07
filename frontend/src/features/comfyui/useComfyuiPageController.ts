import { useDeferredValue, useMemo, useState } from 'react'
import { filterComfyuiWorkflows } from '@/features/comfyui/comfyui-utils'
import { useComfyuiController } from '@/features/comfyui/useComfyuiController'

/**
 * ComfyUI workflow 页面的独立 controller。
 *
 * 该 hook 只暴露 ComfyUI workflow 状态以及一个本地搜索框，
 * 搜索框在客户端过滤 workflow。
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

export type ComfyuiPageController = ReturnType<typeof useComfyuiPageController>
