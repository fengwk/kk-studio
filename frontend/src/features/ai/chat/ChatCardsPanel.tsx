import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { ChatCard } from '@/features/ai/chat/ChatCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { useI18n } from '@/shared/i18n'
import { CatalogSetupGuide } from '@/features/ai/chat/CatalogSetupGuide'
import { isCatalogSetupComplete, type CatalogSetupState } from '@/features/ai/chat/catalog-setup'

export function ChatCardsPanel({
  chats,
  agents,
  onCreate,
  onEdit,
  onDelete,
  deletePending = false,
  setup,
}: {
  chats: ChatDTO[]
  agents: AgentDefinitionDTO[]
  onCreate: () => void
  onEdit?: (chat: ChatDTO) => void
  onDelete?: (chat: ChatDTO) => void
  deletePending?: boolean
  /** Catalog 是否已具备对话所需资源；未知（加载中）时不渲染引导。 */
  setup?: CatalogSetupState
}) {
  const { t } = useI18n()
  return (
    <>
      {setup && !isCatalogSetupComplete(setup) ? <CatalogSetupGuide state={setup} /> : null}
      <ResourceGrid>
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
      </ResourceGrid>
    </>
  )
}
