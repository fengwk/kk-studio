import { AgentsPanel } from '@/features/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import { AiConsoleFrame } from '@/app/ai-console/AiConsoleFrame'
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
