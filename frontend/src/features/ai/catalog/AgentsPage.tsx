import { AgentsPanel } from '@/features/ai/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/ai/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/ai/catalog/CatalogRuntimeContext'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function AgentsPage({ children }: ExtensionComponentProps) {
  return (
    <CatalogRuntime scope="agents">
      <AgentsFrame>{children}</AgentsFrame>
    </CatalogRuntime>
  )
}

function AgentsResourcePanel() {
  const controller = useCatalogRuntime()
  return <AgentsPanel {...controller.agentPanelProps} />
}

function AgentsFrame({ children }: ExtensionComponentProps) {
  const controller = useCatalogRuntime()
  return (
    <AiConsoleFrame
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      mutationError={controller.mutationError}
      content={<AgentsResourcePanel />}
    >
      {children}
    </AiConsoleFrame>
  )
}
