import { lazy, Suspense } from 'react'
import { useOptionalCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import { useI18n } from '@/shared/i18n'

const ResourceEditorModal = lazy(async () => {
  const module = await import('@/features/catalog/AiConsoleResourceEditorModal')
  return { default: module.ResourceEditorModal }
})
const ConfirmActionModal = lazy(async () => {
  const module = await import('@/shared/ui/overlays/ConfirmActionModal')
  return { default: module.ConfirmActionModal }
})

export function ResourceEditorDialog() {
  const { t } = useI18n()
  const controller = useOptionalCatalogRuntime()
  if (!controller?.resourceEditorModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingResourceEditor')}</div>}>
      <ResourceEditorModal {...controller.resourceEditorModal} />
    </Suspense>
  )
}
export function ResourceDeleteDialog() {
  const { t } = useI18n()
  const controller = useOptionalCatalogRuntime()
  if (!controller?.resourceDeleteConfirmModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingConfirmDialog')}</div>}>
      <ConfirmActionModal {...controller.resourceDeleteConfirmModal} className="modal-touch-target" />
    </Suspense>
  )
}
