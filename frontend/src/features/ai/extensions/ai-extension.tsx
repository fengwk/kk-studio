import { lazy, Suspense } from 'react'
import {
  ChatCardsPanel,
  ChatWorkspacePage,
  CreateChatModal,
} from '@/features/ai/chat'
import { ChatRuntime } from '@/features/ai/chat/ChatRuntime'
import {
  useChatRuntime,
  useOptionalChatRuntime,
} from '@/features/ai/chat/ChatRuntimeContext'
import { useOptionalCatalogRuntime } from '@/features/ai/catalog/CatalogRuntimeContext'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

const AgentsPage = lazy(async () => {
  const module = await import('@/features/ai/catalog/AgentsPage')
  return { default: module.AgentsPage }
})
const ModelsPage = lazy(async () => {
  const module = await import('@/features/ai/catalog/ModelsPage')
  return { default: module.ModelsPage }
})
const ProvidersPage = lazy(async () => {
  const module = await import('@/features/ai/catalog/ProvidersPage')
  return { default: module.ProvidersPage }
})
const EnvironmentsPage = lazy(async () => {
  const module = await import('@/features/ai/environment/EnvironmentsPage')
  return { default: module.EnvironmentsPage }
})
const ResourceEditorModal = lazy(async () => {
  const module = await import('@/features/ai/catalog/AiConsoleResourceEditorModal')
  return { default: module.ResourceEditorModal }
})
const ConfirmActionModal = lazy(async () => {
  const module = await import('@/shared/ui/console/ConfirmActionModal')
  return { default: module.ConfirmActionModal }
})

export function ChatsPage({ children }: ExtensionComponentProps) {
  return (
    <ChatRuntime>
      <ChatsFrame>{children}</ChatsFrame>
    </ChatRuntime>
  )
}

function ChatsPanel() {
  const controller = useChatRuntime()
  return <ChatCardsPanel {...controller.chatPanelProps} />
}

function ChatsFrame({ children }: ExtensionComponentProps) {
  const controller = useChatRuntime()
  return (
    <AiConsoleFrame
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      mutationError={controller.mutationError}
      content={<ChatsPanel />}
    >
      {children}
    </AiConsoleFrame>
  )
}

export function AgentsRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingAgent')}</div>}>
      <AgentsPage>{children}</AgentsPage>
    </Suspense>
  )
}

export function ModelsRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingModel')}</div>}>
      <ModelsPage>{children}</ModelsPage>
    </Suspense>
  )
}

export function ProvidersRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingProvider')}</div>}>
      <ProvidersPage>{children}</ProvidersPage>
    </Suspense>
  )
}

export function CreateChatDialog() {
  const controller = useOptionalChatRuntime()
  return controller ? <CreateChatModal {...controller.createChatModal} /> : null
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
      <ConfirmActionModal {...controller.resourceDeleteConfirmModal} />
    </Suspense>
  )
}

export function EnvironmentsRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingEnvironment')}</div>}>
      <EnvironmentsPage />
      {children}
    </Suspense>
  )
}

export function ChatWorkspaceRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <ChatWorkspacePage />
      {children}
    </>
  )
}
