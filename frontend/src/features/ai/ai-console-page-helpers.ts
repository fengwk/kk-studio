import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export function resolveSessionAgentId(agentId: string | undefined, selectedAgentId: string, agents: AgentDefinitionDTO[]): string {
  return agentId || selectedAgentId || (agents[0] ? String(agents[0].id) : '')
}
