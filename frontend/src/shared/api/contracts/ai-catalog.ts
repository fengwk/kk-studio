import type {
  AgentDefinitionUpdateDTO,
  AgentModelUpdateDTO,
  AgentProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

/** Provider 类型的稳定 wire 值；DTO 声明为字符串，表单与草稿按这里收窄。 */
export const AGENT_PROVIDER_TYPES = ['openai', 'openai_responses', 'anthropic', 'google'] as const
export type AgentProviderType = (typeof AGENT_PROVIDER_TYPES)[number]

export function isAgentProviderType(value: string): value is AgentProviderType {
  return (AGENT_PROVIDER_TYPES as readonly string[]).includes(value)
}

/** 更新请求体去掉乐观锁版本后的可编辑部分；编辑表单整体替换这些字段。 */
export type AgentProviderEditablePropertiesDTO = Omit<AgentProviderUpdateDTO, 'expectedVersion'>
export type AgentModelEditablePropertiesDTO = Omit<AgentModelUpdateDTO, 'expectedVersion'>
export type AgentDefinitionEditablePropertiesDTO = Omit<AgentDefinitionUpdateDTO, 'expectedVersion'>
