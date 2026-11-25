import { AgentsPanel } from '@/features/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import { ResourcePageLayout } from '@/shared/ui/cards/ResourcePageLayout'
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
    <ResourcePageLayout
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      onRetry={controller.retry}
      mutationError={controller.mutationError}
      content={<AgentsResourcePanel />}
    >
      {children}
    </ResourcePageLayout>
  )
}
