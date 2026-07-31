import { ModelsPanel } from '@/features/ai/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/ai/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/ai/catalog/CatalogRuntimeContext'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
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
    <AiConsoleFrame
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      mutationError={controller.mutationError}
      content={<ModelsResourcePanel />}
    >
      {children}
    </AiConsoleFrame>
  )
}
