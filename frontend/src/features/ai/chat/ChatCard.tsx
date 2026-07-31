import { ChevronRight, MessageSquare } from 'lucide-react'
import { useNavigate } from 'react-router'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { useI18n } from '@/shared/i18n'

export function ChatCard({
  chat,
  agents,
}: {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
}) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const label = chat.title || chat.id
  const defaultAgent = chat.defaultAgentId
    ? agents.find((agent) => String(agent.id) === String(chat.defaultAgentId))
    : undefined
  const agentLabel = defaultAgent
      ? defaultAgent.name
      : t('ai.chat.missingAgent')

  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <MessageSquare aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{chat.title || t('ai.chat.untitled')}</h3>
            <p>{t('ai.chat.chatLabel')}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label={t('ai.chat.defaultAgent')} value={agentLabel} />
        <MetaRow label={t('ai.chat.updated')} value={formatBackendDate(chat.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={t('ai.chat.enterAria', { label })}
          onClick={() => navigate(`/chats/${encodeURIComponent(chat.id)}`)}
        >
          <ChevronRight aria-hidden="true" />
          {t('ai.catalog.action.enterConversation')}
        </button>
      </div>
    </article>
  )
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta-row">
      <span className="lbl">{label}</span>
      <span className="val">{value}</span>
    </div>
  )
}
