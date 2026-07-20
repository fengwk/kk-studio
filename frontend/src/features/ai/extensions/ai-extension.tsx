/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren, type ReactNode } from 'react'
import { SearchField, StateBlock } from '@/features/ai/AiConsoleCards'
import { ConfirmActionModal, ResourceEditorModal } from '@/features/ai/AiConsoleModals'
import { AgentsPanel, ChatCardsPanel, ModelsPanel, ProvidersPanel } from '@/features/ai/AiConsolePanels'
import { ChatWorkspacePage } from '@/features/ai/ChatWorkspacePage'
import { ComfyuiWorkflowEditorModal } from '@/features/ai/ComfyuiWorkflowEditorModal'
import { ComfyuiWorkflowsPanel } from '@/features/ai/ComfyuiWorkflowsPanel'
import { ComfyuiRunModal } from '@/features/ai/ComfyuiRunModal'
import { CreateChatModal } from '@/features/ai/CreateChatModal'
import { EnvironmentsPage } from '@/features/ai/EnvironmentsPage'
import { useAiConsoleController } from '@/features/ai/useAiConsoleController'
import { useComfyuiPageController } from '@/features/ai/useComfyuiPageController'
import type { ExtensionComponentProps, TrustedReactExtension } from '@/platform/extensions/types'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'

type AiConsoleController = ReturnType<typeof useAiConsoleController>
const AiConsoleContext = createContext<AiConsoleController | null>(null)

type ComfyuiPageController = ReturnType<typeof useComfyuiPageController>
const ComfyuiContext = createContext<ComfyuiPageController | null>(null)

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
          <StateBlock title={controller.mutationError instanceof Error ? controller.mutationError.message : '资源操作失败'} tone="danger" />
        )}
        {!controller.busy && !controller.error && content}
      </div>
      {children}
    </section>
  )
}

function ChatsPage({ children }: ExtensionComponentProps) {
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

function AgentsPage({ children }: ExtensionComponentProps) {
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

function ModelsPage({ children }: ExtensionComponentProps) {
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

function ProvidersPage({ children }: ExtensionComponentProps) {
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

function ComfyuiPage({ children }: ExtensionComponentProps) {
  return (
    <ComfyuiRuntime>
      <ComfyuiFrame content={<ComfyuiPanel />}>
        {children}
      </ComfyuiFrame>
      <ComfyuiRunModalHost />
    </ComfyuiRuntime>
  )
}

function ComfyuiRuntime({ children }: PropsWithChildren) {
  const controller = useComfyuiPageController()
  return <ComfyuiContext.Provider value={controller}>{children}</ComfyuiContext.Provider>
}

function useOptionalComfyui() {
  return useContext(ComfyuiContext)
}

function ComfyuiFrame({ content, children }: ExtensionComponentProps & { content: ReactNode }) {
  const controller = useContext(ComfyuiContext)
  if (!controller) {
    throw new Error('ComfyuiRuntime is required')
  }
  return (
    <section className="screen active">
      <nav className="subbar">
        <div className="ai-mark">ComfyUI</div>
        <SearchField value={controller.search} onChange={controller.setSearch} />
      </nav>
      <div className="screen-body">
        {controller.busy && <StateBlock title="正在加载 ComfyUI 工作流" />}
        {controller.error && <StateBlock title={controller.error instanceof Error ? controller.error.message : '工作流加载失败'} tone="danger" />}
        {controller.mutationError && (
          <StateBlock title={controller.mutationError instanceof Error ? controller.mutationError.message : '工作流操作失败'} tone="danger" />
        )}
        {!controller.busy && !controller.error && content}
      </div>
      {children}
    </section>
  )
}

function ComfyuiPanel() {
  const controller = useContext(ComfyuiContext)
  if (!controller) {
    throw new Error('ComfyuiRuntime is required')
  }
  return <ComfyuiWorkflowsPanel {...controller.comfyuiPanelProps} />
}

function ComfyuiRunModalHost() {
  const controller = useContext(ComfyuiContext)
  if (!controller) {
    return null
  }
  const runModal = controller.comfyuiRunModal
  if (!runModal.workflow) {
    return null
  }
  return (
    <ComfyuiRunModal
      key={String(runModal.workflow.id)}
      workflow={runModal.workflow}
      onClose={runModal.onClose}
    />
  )
}

function CreateChatDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <CreateChatModal {...controller.createChatModal} /> : null
}

function ResourceEditorDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ResourceEditorModal {...controller.resourceEditorModal} /> : null
}

function ResourceDeleteDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ConfirmActionModal {...controller.resourceDeleteConfirmModal} /> : null
}

function ComfyuiWorkflowEditorDialog() {
  const controller = useOptionalComfyui()
  return controller ? <ComfyuiWorkflowEditorModal {...controller.comfyuiEditorModal} /> : null
}

function ComfyuiDeleteDialog() {
  const controller = useOptionalComfyui()
  return controller ? <ConfirmActionModal {...controller.comfyuiDeleteConfirmModal} /> : null
}

function EnvironmentsRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <EnvironmentsPage />
      {children}
    </>
  )
}

function ChatWorkspaceRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <ChatWorkspacePage />
      {children}
    </>
  )
}

export const aiExtension: TrustedReactExtension = {
  id: 'builtin.ai',
  pages: [
    { id: 'ai.chats', path: 'chats', component: ChatsPage, priority: 100 },
    { id: 'ai.chat-workspace', path: 'chats/:chatId', component: ChatWorkspaceRoute, priority: 100 },
    { id: 'ai.agents', path: 'agents', component: AgentsPage, priority: 100 },
    { id: 'ai.models', path: 'models', component: ModelsPage, priority: 100 },
    { id: 'ai.providers', path: 'providers', component: ProvidersPage, priority: 100 },
    { id: 'ai.environments', path: 'environments', component: EnvironmentsRoute, priority: 100 },
    { id: 'ai.comfyui', path: 'comfyui', component: ComfyuiPage, priority: 100 },
  ],
  navigation: [
    { id: 'ai.nav.chats', label: 'Chat', path: 'chats', priority: 100 },
    { id: 'ai.nav.agents', label: 'Agent', path: 'agents', priority: 100 },
    { id: 'ai.nav.models', label: 'Model', path: 'models', priority: 100 },
    { id: 'ai.nav.providers', label: 'Provider', path: 'providers', priority: 100 },
    { id: 'ai.nav.environments', label: 'Environment', path: 'environments', priority: 90 },
  ],
  dialogs: [
    { id: 'ai.create-chat', component: CreateChatDialog },
    { id: 'ai.resource-editor', component: ResourceEditorDialog },
    { id: 'ai.delete-resource', component: ResourceDeleteDialog },
    { id: 'ai.comfyui-editor', component: ComfyuiWorkflowEditorDialog },
    { id: 'ai.comfyui-delete', component: ComfyuiDeleteDialog },
  ],
}
