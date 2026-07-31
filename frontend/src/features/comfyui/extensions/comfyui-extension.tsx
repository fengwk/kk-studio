import { lazy, Suspense } from 'react'
import { useOptionalComfyui } from '@/features/comfyui/ComfyuiContext'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

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
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('comfyui.extension.loading')}</div>}>
      <ComfyuiPage>{children}</ComfyuiPage>
    </Suspense>
  )
}

export function ComfyuiWorkflowEditorDialog() {
  const controller = useOptionalComfyui()
  const { t } = useI18n()
  if (!controller?.comfyuiEditorModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('comfyui.extension.loadingEditor')}</div>}>
      <ComfyuiWorkflowEditorModal {...controller.comfyuiEditorModal} />
    </Suspense>
  )
}

export function ComfyuiDeleteDialog() {
  const controller = useOptionalComfyui()
  const { t } = useI18n()
  if (!controller?.comfyuiDeleteConfirmModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('comfyui.extension.loadingConfirmDialog')}</div>}>
      <ConfirmActionModal {...controller.comfyuiDeleteConfirmModal} />
    </Suspense>
  )
}
