import type { ReactNode } from 'react'
import { ChevronRight, MessageSquare, Pencil, Trash2 } from 'lucide-react'
import { useNavigate } from 'react-router'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { useI18n } from '@/shared/i18n'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { Button } from '@/shared/ui/controls/Button'

export function ChatCard({
  chat,
  agents,
  onEdit,
  onDelete,
  deletePending = false,
}: {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
  onEdit?: () => void
  onDelete?: () => void
  deletePending?: boolean
}) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const label = chat.title || chat.id
  const agent = chat.agentName
    ? agents.find((item) => item.name === chat.agentName)
    : undefined
  const staleAgent = Boolean(chat.agentName) && !agent
  const agentLabel: ReactNode = staleAgent ? (
    <span
      className="chat-card-agent-unavailable"
      aria-disabled="true"
      aria-label={`${chat.agentName} ${t('ai.chat.missingAgent')}`}
    >
      <span>{chat.agentName}</span>
      <span className="chat-card-agent-unavailable-label">{t('ai.chat.missingAgent')}</span>
    </span>
  ) : (
    agent?.name || chat.agentName || t('ai.chat.missingAgent')
  )

  return (
    <ResourceCard
      icon={<MessageSquare aria-hidden="true" />}
      title={chat.title || t('ai.chat.untitled')}
      subtitle={t('ai.chat.chatLabel')}
      meta={[
        [t('ai.chat.agent'), agentLabel],
        [t('ai.chat.updated'), formatBackendDate(chat.updateTime)],
      ]}
      actions={
        <>
          <Button
            size="compact"
            aria-label={t('ai.chat.enterAria', { label })}
            onClick={() => navigate(`/chats/${encodeURIComponent(chat.id)}`)}
          >
            <ChevronRight aria-hidden="true" />
            {t('ai.catalog.action.enterConversation')}
          </Button>
          {onEdit ? (
            <Button
              variant="ghost"
              size="compact"
              aria-label={`${t('ai.catalog.action.edit')} ${label}`}
              onClick={onEdit}
            >
              <Pencil aria-hidden="true" />
              {t('ai.catalog.action.edit')}
            </Button>
          ) : null}
          {onDelete ? (
            <Button
              variant="ghost"
              size="compact"
              danger
              aria-label={`${t('ai.catalog.action.delete')} ${label}`}
              onClick={onDelete}
              disabled={deletePending}
            >
              <Trash2 aria-hidden="true" />
              {t('ai.catalog.action.delete')}
            </Button>
          ) : null}
        </>
      }
    />
  )
}
