import { createContext, useContext, type PropsWithChildren, type ReactNode } from 'react'
import {
  AgentsPanel,
  ConfirmActionModal,
  ModelsPanel,
  ProvidersPanel,
  ResourceEditorModal,
} from '@/features/ai/catalog'
import {
  ChatCardsPanel,
  ChatWorkspacePage,
  CreateChatModal,
} from '@/features/ai/chat'
import { EnvironmentsPage } from '@/features/ai/environment'
import { HarnessSettingsPage } from '@/features/ai/settings'
import {
  SearchField,
  StateBlock,
} from '@/features/ai/shared/AiConsoleCommonCards'
import { useAiConsoleController } from '@/features/ai/extensions/useAiConsoleController'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'

type AiConsoleController = ReturnType<typeof useAiConsoleController>
const AiConsoleContext = createContext<AiConsoleController | null>(null)

function AiConsoleRuntime({ children }: PropsWithChildren) {
  const controller = useAiConsoleController()
  return <AiConsoleContext.Provider value={controller}>{children}</AiConsoleContext.Provider>
}

function useAiConsole() {
  const controller = useContext(AiConsoleContext)
  if (!controller) {
    throw new Error('AiConsoleRuntime is required')
  }
  return controller
}

function useOptionalAiConsole() {
  return useContext(AiConsoleContext)
}

function AiConsoleFrame({ content, children }: ExtensionComponentProps & { content: ReactNode }) {
  const controller = useAiConsole()
  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
        <SearchField value={controller.search} onChange={controller.setSearch} />
      </nav>
      <div className="screen-body">
        {controller.busy && <StateBlock title="正在加载资源" />}
        {controller.error && <StateBlock title={controller.error instanceof Error ? controller.error.message : '资源加载失败'} tone="danger" />}
        {controller.mutationError && (
          <StateBlock
            title={
              controller.mutationError instanceof Error
                ? // 外层仅展示无模态时的操作错误；文案已在 controller 侧尽量友好
                  controller.mutationError.message
                : '操作失败，请稍后重试'
            }
            tone="danger"
          />
        )}
        {!controller.busy && !controller.error && content}
      </div>
      {children}
    </section>
  )
}

export function ChatsPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime>
      <AiConsoleFrame content={<ChatsPanel />}>
        {children}
      </AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function ChatsPanel() {
  const controller = useAiConsole()
  return <ChatCardsPanel {...controller.chatPanelProps} />
}

export function AgentsPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime>
      <AiConsoleFrame content={<AgentsResourcePanel />}>
        {children}
      </AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function AgentsResourcePanel() {
  const controller = useAiConsole()
  return <AgentsPanel {...controller.agentPanelProps} />
}

export function ModelsPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime>
      <AiConsoleFrame content={<ModelsResourcePanel />}>
        {children}
      </AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function ModelsResourcePanel() {
  const controller = useAiConsole()
  return <ModelsPanel {...controller.modelPanelProps} />
}

export function ProvidersPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime>
      <AiConsoleFrame content={<ProvidersResourcePanel />}>
        {children}
      </AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function ProvidersResourcePanel() {
  const controller = useAiConsole()
  return <ProvidersPanel {...controller.providerPanelProps} />
}

export function CreateChatDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <CreateChatModal {...controller.createChatModal} /> : null
}

export function ResourceEditorDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ResourceEditorModal {...controller.resourceEditorModal} /> : null
}

export function ResourceDeleteDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ConfirmActionModal {...controller.resourceDeleteConfirmModal} /> : null
}

export function EnvironmentsRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <EnvironmentsPage />
      {children}
    </>
  )
}

export function HarnessSettingsRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <HarnessSettingsPage />
      {children}
    </>
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
