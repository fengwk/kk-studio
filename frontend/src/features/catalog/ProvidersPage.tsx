import { ProvidersPanel } from '@/features/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import { ResourcePageLayout } from '@/shared/ui/cards/ResourcePageLayout'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function ProvidersPage({ children }: ExtensionComponentProps) {
  return (
    <CatalogRuntime scope="providers">
      <ProvidersFrame>{children}</ProvidersFrame>
    </CatalogRuntime>
  )
}

function ProvidersResourcePanel() {
  const controller = useCatalogRuntime()
  return <ProvidersPanel {...controller.providerPanelProps} />
}

function ProvidersFrame({ children }: ExtensionComponentProps) {
  const controller = useCatalogRuntime()
  return (
    <ResourcePageLayout
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      onRetry={controller.retry}
      mutationError={controller.mutationError}
      content={<ProvidersResourcePanel />}
    >
      {children}
    </ResourcePageLayout>
  )
}
