import { AgentResourceCard, CreateCard, ModelResourceCard, ProviderResourceCard } from '@/features/ai/AiConsoleCards'
import { ChatCard } from '@/features/ai/ChatCard'
import type {
  AgentDefinitionDTO,
  AgentModelWithProviderDTO,
  AgentProviderDTO,
  ChatDTO,
} from '@/shared/api/contracts'

export function ChatCardsPanel({
  chats,
  agents,
  onCreate,
}: {
  chats: ChatDTO[]
  agents: AgentDefinitionDTO[]
  onCreate: () => void
}) {
  return (
    <div className="cards-grid">
      <CreateCard title="新建 Chat" subtitle="创建持久 Chat 工作区（可选默认 Agent）" onClick={onCreate} />
      {chats.map((chat) => (
        <ChatCard key={chat.id} chat={chat} agents={agents} />
      ))}
    </div>
  )
}

export function AgentsPanel({
  agents,
  models = [],
  deletePending,
  onCreate,
  onEdit,
  onDelete,
}: {
  agents: AgentDefinitionDTO[]
  models?: AgentModelWithProviderDTO[]
  deletePending: boolean
  onCreate: () => void
  onEdit: (agent: AgentDefinitionDTO) => void
  onDelete: (agent: AgentDefinitionDTO) => void
}) {
  return (
    <div className="cards-grid">
      <CreateCard title="新建 Agent" subtitle="配置聚合：Model / Environment / tools / skills / policy" onClick={onCreate} />
      {agents.map((agent) => (
        <AgentResourceCard
          key={agent.id}
          agent={agent}
          models={models}
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
  models: AgentModelWithProviderDTO[]
  deletePending: boolean
  onCreate: () => void
  onEdit: (model: AgentModelWithProviderDTO) => void
  onDelete: (model: AgentModelWithProviderDTO) => void
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
      <CreateCard title="新建 Provider" subtitle="配置供应商与凭据" onClick={onCreate} />
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
