import { createContext, useContext, type PropsWithChildren, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { SearchField, StateBlock } from '@/features/ai/AiConsoleCards'
import { ConfirmActionModal, ResourceEditorModal } from '@/features/ai/AiConsoleModals'
import { AgentsPanel, ChatCardsPanel, ModelsPanel, ProvidersPanel } from '@/features/ai/AiConsolePanels'
import { ChatWorkspacePage } from '@/features/ai/ChatWorkspacePage'
import { ComfyuiWorkflowEditorModal } from '@/features/ai/ComfyuiWorkflowEditorModal'
import { ComfyuiWorkflowsPanel } from '@/features/ai/ComfyuiWorkflowsPanel'
import { ComfyuiRunModal } from '@/features/ai/ComfyuiRunModal'
import { CreateChatModal } from '@/features/ai/CreateChatModal'
import { EnvironmentsPage } from '@/features/ai/EnvironmentsPage'
import { HarnessSettingsPage } from '@/features/ai/HarnessSettingsPage'
import { useAiConsoleController } from '@/features/ai/useAiConsoleController'
import { useComfyuiPageController } from '@/features/ai/useComfyuiPageController'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
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

export function ComfyuiPage({ children }: ExtensionComponentProps) {
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
        <nav className="subnav" aria-label="Tools">
          <Link className="active" to="/comfyui">
            ComfyUI
          </Link>
        </nav>
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

export function ComfyuiWorkflowEditorDialog() {
  const controller = useOptionalComfyui()
  return controller ? <ComfyuiWorkflowEditorModal {...controller.comfyuiEditorModal} /> : null
}

export function ComfyuiDeleteDialog() {
  const controller = useOptionalComfyui()
  return controller ? <ConfirmActionModal {...controller.comfyuiDeleteConfirmModal} /> : null
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
