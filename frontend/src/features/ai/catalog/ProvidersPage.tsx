import { ProvidersPanel } from '@/features/ai/catalog/AiConsolePanels'
import {
  AiConsoleFrame,
  AiConsoleRuntime,
  useAiConsole,
} from '@/features/ai/extensions/AiConsoleRuntime'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function ProvidersPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime scope="providers">
      <AiConsoleFrame content={<ProvidersResourcePanel />}>{children}</AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function ProvidersResourcePanel() {
  const controller = useAiConsole()
  return <ProvidersPanel {...controller.providerPanelProps} />
}
