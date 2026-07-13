import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export function resolveSessionAgentName(agentName: string | undefined, selectedAgentName: string, agents: AgentDefinitionDTO[]): string {
  return agentName || selectedAgentName || agents[0]?.name || ''
}
