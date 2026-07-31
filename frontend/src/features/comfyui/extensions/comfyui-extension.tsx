import { lazy, Suspense } from 'react'
import { useOptionalComfyui } from '@/features/comfyui/ComfyuiRuntime'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

const ComfyuiPage = lazy(async () => {
  const module = await import('@/features/comfyui/ComfyuiPage')
  return { default: module.ComfyuiPage }
})
const ComfyuiWorkflowEditorModal = lazy(async () => {
  const module = await import('@/features/comfyui/ComfyuiWorkflowEditorModal')
  return { default: module.ComfyuiWorkflowEditorModal }
})
const ConfirmActionModal = lazy(async () => {
  const module = await import('@/shared/ui/console/ConfirmActionModal')
  return { default: module.ConfirmActionModal }
})

export function ComfyuiRoute({ children }: ExtensionComponentProps) {
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载 ComfyUI</div>}>
      <ComfyuiPage>{children}</ComfyuiPage>
    </Suspense>
  )
}

export function ComfyuiWorkflowEditorDialog() {
  const controller = useOptionalComfyui()
  if (!controller?.comfyuiEditorModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载工作流编辑器</div>}>
      <ComfyuiWorkflowEditorModal {...controller.comfyuiEditorModal} />
    </Suspense>
  )
}

export function ComfyuiDeleteDialog() {
  const controller = useOptionalComfyui()
  if (!controller?.comfyuiDeleteConfirmModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载确认对话框</div>}>
      <ConfirmActionModal {...controller.comfyuiDeleteConfirmModal} />
    </Suspense>
  )
}
