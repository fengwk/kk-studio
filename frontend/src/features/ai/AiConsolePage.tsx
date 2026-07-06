import { SearchField, StateBlock, TabButton } from '@/features/ai/AiConsoleCards'
import { ConfirmActionModal, CreateSessionModal, EditSessionModal, ResourceEditorModal } from '@/features/ai/AiConsoleModals'
import { AgentsPanel, ChatSessionsPanel, ModelsPanel, ProvidersPanel } from '@/features/ai/AiConsolePanels'
import type { AiConsoleTab } from '@/features/ai/ai-console-types'
import { useAiConsoleController } from '@/features/ai/useAiConsoleController'
import { AppShell } from '@/platform/shell/AppShell'

interface AiConsolePageProps {
  initialTab: AiConsoleTab
}

export function AiConsolePage({ initialTab }: AiConsolePageProps) {
  const controller = useAiConsoleController()

  function renderCurrentPanel() {
    if (controller.busy || controller.error) {
      return null
    }

    switch (initialTab) {
      case 'chat':
        return <ChatSessionsPanel {...controller.chatPanelProps} />
      case 'agent':
        return <AgentsPanel {...controller.agentPanelProps} />
      case 'model':
        return <ModelsPanel {...controller.modelPanelProps} />
      case 'provider':
        return <ProvidersPanel {...controller.providerPanelProps} />
    }
  }

  return (
    <AppShell>
      <section className="screen active">
        <nav className="subbar">
          <div className="ai-mark">AI</div>
          <div className="subnav" role="tablist" aria-label="AI resources">
            {controller.tabs.map((tab) => (
              <TabButton key={tab} tab={tab} activeTab={initialTab} onClick={() => controller.navigateToTab(tab)} />
            ))}
          </div>
          <SearchField value={controller.search} onChange={controller.setSearch} />
        </nav>

        <div className="screen-body">
          {controller.busy && <StateBlock title="正在加载资源" />}
          {controller.error && <StateBlock title={controller.error instanceof Error ? controller.error.message : '资源加载失败'} tone="danger" />}
          {controller.mutationError && (
            <StateBlock title={controller.mutationError instanceof Error ? controller.mutationError.message : '资源操作失败'} tone="danger" />
          )}
          {renderCurrentPanel()}
        </div>
      </section>

      <CreateSessionModal {...controller.createSessionModal} />
      <EditSessionModal {...controller.editSessionModal} />
      <ResourceEditorModal {...controller.resourceEditorModal} />
      <ConfirmActionModal {...controller.sessionDeleteConfirmModal} />
      <ConfirmActionModal {...controller.resourceDeleteConfirmModal} />
    </AppShell>
  )
}

export type { AiConsoleTab }
