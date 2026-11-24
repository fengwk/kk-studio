import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { ChatCard } from '@/features/chat/ChatCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { useI18n } from '@/shared/i18n'
import { CatalogSetupGuide } from '@/features/chat/CatalogSetupGuide'
import { isCatalogSetupComplete, type CatalogSetupState } from '@/features/chat/catalog-setup'
import { Button } from '@/shared/ui/controls/Button'
import type { AgentDTO, ChatDTO } from '@/shared/api/generated/contracts'
import './chat-cards.css'

export function ChatCardsPanel({
  chats,
  agents,
  onCreate,
  onEdit,
  onDelete,
  deletePending = false,
  setup,
  hasMore = false,
  loadingMore = false,
  loadMoreError = null,
  onLoadMore,
}: {
  chats: ChatDTO[]
  agents: AgentDTO[]
  onCreate: () => void
  onEdit?: (chat: ChatDTO) => void
  onDelete?: (chat: ChatDTO) => void
  deletePending?: boolean
  /** Catalog 是否已具备对话所需资源；未知（加载中）时不渲染引导。 */
  setup?: CatalogSetupState
  /** 首页之后是否还有未加载的对话页。 */
  hasMore?: boolean
  loadingMore?: boolean
  /** 上一次「加载更多」失败的本地化文案；失败不影响已加载的卡片。 */
  loadMoreError?: string | null
  onLoadMore?: () => void
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
      {hasMore && onLoadMore ? (
        <div className="chat-cards-load-more">
          {loadMoreError ? (
            <p className="chat-cards-load-more-error" role="alert">
              {loadMoreError}
            </p>
          ) : null}
          <Button variant="secondary" loading={loadingMore} onClick={onLoadMore}>
            {loadMoreError ? t('shared.retry') : t('ai.chat.loadMore')}
          </Button>
        </div>
      ) : null}
    </>
  )
}
