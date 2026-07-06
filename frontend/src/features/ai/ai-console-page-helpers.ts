import type { AiConsoleTab } from '@/features/ai/ai-console-types'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export const aiConsoleTabs: AiConsoleTab[] = ['chat', 'agent', 'model', 'provider']

export function resolveSessionAgentName(agentName: string | undefined, selectedAgentName: string, agents: AgentDefinitionDTO[]): string {
  return agentName || selectedAgentName || agents[0]?.name || ''
}
