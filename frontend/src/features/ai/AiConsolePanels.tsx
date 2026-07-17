import { AgentResourceCard, CreateCard, ModelResourceCard, ProviderResourceCard, SessionCard } from '@/features/ai/AiConsoleCards'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO, HarnessSessionDTO } from '@/shared/api/contracts'

export function ChatSessionsPanel({
  sessions,
  agentsById,
  onCreate,
}: {
  sessions: HarnessSessionDTO[]
  agentsById: Map<string, AgentDefinitionDTO>
  onCreate: () => void
}) {
  return (
    <div className="cards-grid">
      <CreateCard title="新建 Chat" subtitle="选择一个 Agent 创建云端会话" onClick={onCreate} />
      {sessions.map((session) => (
        <SessionCard
          key={session.sessionId}
          session={session}
          agent={agentsById.get(session.agentDefinitionId)}
        />
      ))}
    </div>
  )
}

export function AgentsPanel({
  agents,
  deletePending,
  onCreate,
  onStart,
  onEdit,
  onDelete,
}: {
  agents: AgentDefinitionDTO[]
  deletePending: boolean
  onCreate: () => void
  onStart: (agent: AgentDefinitionDTO) => void
  onEdit: (agent: AgentDefinitionDTO) => void
  onDelete: (agent: AgentDefinitionDTO) => void
}) {
  return (
    <div className="cards-grid">
      <CreateCard title="新建 Agent" subtitle="绑定 Provider、Model 与 system prompt" onClick={onCreate} />
      {agents.map((agent) => (
        <AgentResourceCard
          key={agent.id}
          agent={agent}
          onStart={() => onStart(agent)}
          onEdit={() => onEdit(agent)}
          onDelete={() => onDelete(agent)}
          deletePending={deletePending}
        />
      ))}
    </div>
  )
}

export function ModelsPanel({
  models,
  deletePending,
  onCreate,
  onEdit,
  onDelete,
}: {
  models: AgentModelDTO[]
  deletePending: boolean
  onCreate: () => void
  onEdit: (model: AgentModelDTO) => void
  onDelete: (model: AgentModelDTO) => void
}) {
  return (
    <div className="cards-grid">
      <CreateCard title="新建 Model" subtitle="定义模型信息与 variants" onClick={onCreate} />
      {models.map((model) => (
        <ModelResourceCard
          key={model.id}
          model={model}
          onEdit={() => onEdit(model)}
          onDelete={() => onDelete(model)}
          deletePending={deletePending}
        />
      ))}
    </div>
  )
}

export function ProvidersPanel({
  providers,
  deletePending,
  onCreate,
  onEdit,
  onDelete,
}: {
  providers: AgentProviderDTO[]
  deletePending: boolean
  onCreate: () => void
  onEdit: (provider: AgentProviderDTO) => void
  onDelete: (provider: AgentProviderDTO) => void
}) {
  return (
    <div className="cards-grid">
      <CreateCard title="新建 Provider" subtitle="配置连接地址、凭据与超时" onClick={onCreate} />
      {providers.map((provider) => (
        <ProviderResourceCard
          key={provider.id}
          provider={provider}
          onEdit={() => onEdit(provider)}
          onDelete={() => onDelete(provider)}
          deletePending={deletePending}
        />
      ))}
    </div>
  )
}
