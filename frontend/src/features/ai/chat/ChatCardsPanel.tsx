import { CreateCard } from '@/shared/ui/console/AiConsoleCommonCards'
import { ChatCard } from '@/features/ai/chat/ChatCard'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'

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
      <CreateCard
        title="新建 Chat"
        subtitle="创建持久 Chat 工作区（可选默认 Agent）"
        onClick={onCreate}
      />
      {chats.map((chat) => (
        <ChatCard key={chat.id} chat={chat} agents={agents} />
      ))}
    </div>
  )
}
