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
const HarnessSettingsPage = lazy(async () => {
  const module = await import('@/features/ai/settings/HarnessSettingsPage')
  return { default: module.HarnessSettingsPage }
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
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载 Agent</div>}>
      <AgentsPage>{children}</AgentsPage>
    </Suspense>
  )
}

export function ModelsRoute({ children }: ExtensionComponentProps) {
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载 Model</div>}>
      <ModelsPage>{children}</ModelsPage>
    </Suspense>
  )
}

export function ProvidersRoute({ children }: ExtensionComponentProps) {
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载 Provider</div>}>
      <ProvidersPage>{children}</ProvidersPage>
    </Suspense>
  )
}

export function CreateChatDialog() {
  const controller = useOptionalChatRuntime()
  return controller ? <CreateChatModal {...controller.createChatModal} /> : null
}

export function ResourceEditorDialog() {
  const controller = useOptionalCatalogRuntime()
  if (!controller?.resourceEditorModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载资源编辑器</div>}>
      <ResourceEditorModal {...controller.resourceEditorModal} />
    </Suspense>
  )
}

export function ResourceDeleteDialog() {
  const controller = useOptionalCatalogRuntime()
  if (!controller?.resourceDeleteConfirmModal.modal) {
    return null
  }
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载确认对话框</div>}>
      <ConfirmActionModal {...controller.resourceDeleteConfirmModal} />
    </Suspense>
  )
}

export function EnvironmentsRoute({ children }: ExtensionComponentProps) {
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载 Environment</div>}>
      <EnvironmentsPage />
      {children}
    </Suspense>
  )
}

export function HarnessSettingsRoute({ children }: ExtensionComponentProps) {
  return (
    <Suspense fallback={<div className="state-block" role="status">正在加载设置</div>}>
      <HarnessSettingsPage />
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
