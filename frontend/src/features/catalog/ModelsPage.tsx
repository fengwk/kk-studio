import { ModelsPanel } from '@/features/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import { ResourcePageLayout } from '@/shared/ui/cards/ResourcePageLayout'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function ModelsPage({ children }: ExtensionComponentProps) {
  return (
    <CatalogRuntime scope="models">
      <ModelsFrame>{children}</ModelsFrame>
    </CatalogRuntime>
  )
}

function ModelsResourcePanel() {
  const controller = useCatalogRuntime()
  return <ModelsPanel {...controller.modelPanelProps} />
}

function ModelsFrame({ children }: ExtensionComponentProps) {
  const controller = useCatalogRuntime()
  return (
    <ResourcePageLayout
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      onRetry={controller.retry}
      mutationError={controller.mutationError}
      content={<ModelsResourcePanel />}
    >
      {children}
    </ResourcePageLayout>
  )
}
