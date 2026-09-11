import { CreateCard } from '@/shared/ui/console/AiConsoleCommonCards'
import { ChatCard } from '@/features/ai/chat/ChatCard'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { useI18n } from '@/shared/i18n'

export function ChatCardsPanel({
  chats,
  agents,
  onCreate,
  onEdit,
  onDelete,
  deletePending = false,
}: {
  chats: ChatDTO[]
  agents: AgentDefinitionDTO[]
  onCreate: () => void
  onEdit?: (chat: ChatDTO) => void
  onDelete?: (chat: ChatDTO) => void
  deletePending?: boolean
}) {
  const { t } = useI18n()
  return (
    <div className="cards-grid">
      <CreateCard
        title={t('ai.chat.create')}
        subtitle={t('ai.chat.createDescription')}
        onClick={onCreate}
      />
      {chats.map((chat) => (
        <ChatCard
          key={chat.id}
          chat={chat}
          agents={agents}
          onEdit={onEdit ? () => onEdit(chat) : undefined}
          onDelete={onDelete ? () => onDelete(chat) : undefined}
          deletePending={deletePending}
        />
      ))}
    </div>
  )
}
