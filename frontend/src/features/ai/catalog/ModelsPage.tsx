import { ModelsPanel } from '@/features/ai/catalog/AiConsolePanels'
import {
  AiConsoleFrame,
  AiConsoleRuntime,
  useAiConsole,
} from '@/features/ai/extensions/AiConsoleRuntime'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function ModelsPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime scope="models">
      <AiConsoleFrame content={<ModelsResourcePanel />}>{children}</AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function ModelsResourcePanel() {
  const controller = useAiConsole()
  return <ModelsPanel {...controller.modelPanelProps} />
}
