import { AgentsPanel } from '@/features/ai/catalog/AiConsolePanels'
import {
  AiConsoleFrame,
  AiConsoleRuntime,
  useAiConsole,
} from '@/features/ai/extensions/AiConsoleRuntime'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function AgentsPage({ children }: ExtensionComponentProps) {
  return (
    <AiConsoleRuntime scope="agents">
      <AiConsoleFrame content={<AgentsResourcePanel />}>{children}</AiConsoleFrame>
    </AiConsoleRuntime>
  )
}

function AgentsResourcePanel() {
  const controller = useAiConsole()
  return <AgentsPanel {...controller.agentPanelProps} />
}
