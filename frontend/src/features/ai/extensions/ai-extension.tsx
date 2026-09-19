import { lazy, Suspense } from 'react'
import { useOptionalCatalogRuntime } from '@/features/ai/catalog/CatalogRuntimeContext'
import { CreateChatDialog as LazyCreateChatDialog } from '@/features/ai/extensions/CreateChatDialog'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

const ChatsRoute = lazy(() => import('@/features/ai/chat/ChatsRoute'))
const ChatWorkspacePage = lazy(() => import('@/features/ai/chat/ChatWorkspaceRoute'))
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
const McpServersPage = lazy(async () => {
  const module = await import('@/features/ai/mcp/McpServersPage')
  return { default: module.McpServersPage }
})
const SkillPackagesPage = lazy(async () => {
  const module = await import('@/features/ai/skills/SkillPackagesPage')
  return { default: module.SkillPackagesPage }
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
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.chat.loading')}</div>}>
      <ChatsRoute>{children}</ChatsRoute>
    </Suspense>
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

export function SkillPackagesRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.skillPackages.loading')}</div>}>
      <SkillPackagesPage />
      {children}
    </Suspense>
  )
}

export function McpServersRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <Suspense fallback={<div className="state-block" role="status">{t('ai.common.loadingMcpServer')}</div>}>
      <McpServersPage />
      {children}
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
