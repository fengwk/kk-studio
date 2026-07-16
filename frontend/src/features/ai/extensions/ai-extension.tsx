/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren, type ReactNode } from 'react'
import { SearchField, StateBlock } from '@/features/ai/AiConsoleCards'
import { ConfirmActionModal, CreateSessionModal, EditSessionModal, ResourceEditorModal } from '@/features/ai/AiConsoleModals'
import { AgentsPanel, ChatSessionsPanel, ModelsPanel, ProvidersPanel } from '@/features/ai/AiConsolePanels'
import { ComfyuiWorkflowEditorModal } from '@/features/ai/ComfyuiWorkflowEditorModal'
import { ComfyuiWorkflowsPanel } from '@/features/ai/ComfyuiWorkflowsPanel'
import { ComfyuiRunModal } from '@/features/ai/ComfyuiRunModal'
import { AgentSessionPage } from '@/features/ai/AgentSessionPage'
import { useAiConsoleController } from '@/features/ai/useAiConsoleController'
import type { ExtensionComponentProps, TrustedReactExtension } from '@/platform/extensions/types'
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
        <div className="ai-mark">AI</div>
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

function SessionsPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime>
      <AiConsoleFrame content={<SessionsPanel />}>
        {children}
      </AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function SessionsPanel() {
  const controller = useAiConsole()
  return <ChatSessionsPanel {...controller.chatPanelProps} />
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
    <AiConsoleRuntime>
      <AiConsoleFrame content={<ComfyuiPanel />}>
        {children}
      </AiConsoleFrame>
      <ComfyuiRunModalHost />
    </AiConsoleRuntime>
  )
}

function ComfyuiPanel() {
  const controller = useAiConsole()
  return <ComfyuiWorkflowsPanel {...controller.comfyuiPanelProps} />
}

function ComfyuiRunModalHost() {
  const controller = useAiConsole()
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

function CreateSessionDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <CreateSessionModal {...controller.createSessionModal} /> : null
}

function EditSessionDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <EditSessionModal {...controller.editSessionModal} /> : null
}

function ResourceEditorDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ResourceEditorModal {...controller.resourceEditorModal} /> : null
}

function SessionDeleteDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ConfirmActionModal {...controller.sessionDeleteConfirmModal} /> : null
}

function ResourceDeleteDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ConfirmActionModal {...controller.resourceDeleteConfirmModal} /> : null
}

function ComfyuiWorkflowEditorDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ComfyuiWorkflowEditorModal {...controller.comfyuiEditorModal} /> : null
}

function ComfyuiDeleteDialog() {
  const controller = useOptionalAiConsole()
  return controller ? <ConfirmActionModal {...controller.comfyuiDeleteConfirmModal} /> : null
}

export const aiExtension: TrustedReactExtension = {
  id: 'builtin.ai',
  pages: [
    { id: 'ai.sessions', path: 'sessions', component: SessionsPage, priority: 100 },
    { id: 'ai.agents', path: 'agents', component: AgentsPage, priority: 100 },
    { id: 'ai.models', path: 'models', component: ModelsPage, priority: 100 },
    { id: 'ai.providers', path: 'providers', component: ProvidersPage, priority: 100 },
    { id: 'ai.comfyui', path: 'comfyui', component: ComfyuiPage, priority: 100 },
    { id: 'ai.session', path: 'sessions/:sessionId', component: AgentSessionPage, priority: 100 },
  ],
  navigation: [
    { id: 'ai.nav.sessions', label: 'Chat', path: 'sessions', priority: 100 },
    { id: 'ai.nav.agents', label: 'Agent', path: 'agents', priority: 100 },
    { id: 'ai.nav.models', label: 'Model', path: 'models', priority: 100 },
    { id: 'ai.nav.providers', label: 'Provider', path: 'providers', priority: 100 },
    { id: 'ai.nav.comfyui', label: 'Workflow', path: 'comfyui', priority: 100 },
  ],
  dialogs: [
    { id: 'ai.create-session', component: CreateSessionDialog },
    { id: 'ai.edit-session', component: EditSessionDialog },
    { id: 'ai.resource-editor', component: ResourceEditorDialog },
    { id: 'ai.delete-session', component: SessionDeleteDialog },
    { id: 'ai.delete-resource', component: ResourceDeleteDialog },
    { id: 'ai.comfyui-editor', component: ComfyuiWorkflowEditorDialog },
    { id: 'ai.comfyui-delete', component: ComfyuiDeleteDialog },
  ],
}
