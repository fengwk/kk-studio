import { lazy, Suspense } from 'react'
import { useOptionalCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import { CreateChatDialog as LazyCreateChatDialog } from '@/app/ai-console/CreateChatDialog'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

const ChatsRoute = lazy(() => import('@/features/chat/ChatsRoute'))
const ChatWorkspacePage = lazy(() => import('@/features/chat/ChatWorkspaceRoute'))
const ThreadWorkspacePage = lazy(() => import('@/features/chat/thread/ThreadWorkspaceRoute'))
const ResourceEditorModal = lazy(async () => {
  const module = await import('@/features/catalog/AiConsoleResourceEditorModal')
  return { default: module.ResourceEditorModal }
})
const ConfirmActionModal = lazy(async () => {
  const module = await import('@/shared/ui/overlays/ConfirmActionModal')
  return { default: module.ConfirmActionModal }
})

export function ChatsPage({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.chat.loading')}</div>}>
      <ChatsRoute>{children}</ChatsRoute>
    </Suspense>
  )
}

export function CreateChatDialog() {
  return <LazyCreateChatDialog />
}

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

export function ChatWorkspaceRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.chat.loading')}</div>}>
      <ChatWorkspacePage>{children}</ChatWorkspacePage>
    </Suspense>
  )
}

export function ThreadWorkspaceRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.chat.loading')}</div>}>
      <ThreadWorkspacePage>{children}</ThreadWorkspacePage>
    </Suspense>
  )
}
