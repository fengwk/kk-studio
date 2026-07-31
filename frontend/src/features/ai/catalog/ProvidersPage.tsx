import { ProvidersPanel } from '@/features/ai/catalog/AiConsolePanels'
import { CatalogRuntime } from '@/features/ai/catalog/CatalogRuntime'
import { useCatalogRuntime } from '@/features/ai/catalog/CatalogRuntimeContext'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
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
    <AiConsoleFrame
      search={controller.search}
      onSearchChange={controller.setSearch}
      busy={controller.busy}
      error={controller.error}
      mutationError={controller.mutationError}
      content={<ProvidersResourcePanel />}
    >
      {children}
    </AiConsoleFrame>
  )
}
