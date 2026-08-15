import type { ReactNode } from 'react'
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
  const agent = chat.agentName
    ? agents.find((item) => item.name === chat.agentName)
    : undefined
  const staleAgent = Boolean(chat.agentName) && !agent
  const agentLabel = staleAgent ? (
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
        <MetaRow label={t('ai.chat.agent')} value={agentLabel} />
        <MetaRow
          label={t('ai.chat.environment')}
          value={
            chat.environment
              ? `${chat.environment.name} · ${chat.environment.workspacePath}`
              : t('ai.chat.noneEnvironment')
          }
        />
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

function MetaRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="meta-row">
      <span className="lbl">{label}</span>
      <span className="val">{value}</span>
    </div>
  )
}
