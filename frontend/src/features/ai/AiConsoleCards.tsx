import { Bot, ChevronRight, Cpu, MessageSquare, Pencil, Plus, Search, ServerCog, Trash2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO, AgentSessionDTO } from '@/shared/api/contracts'
import type { AiConsoleTab } from '@/features/ai/ai-console-types'
import { formatBackendDate, formatJsonSummary } from '@/features/ai/ai-console-utils'
import { tabLabels } from '@/features/ai/ai-console-types'

export function TabButton({ tab, activeTab, onClick }: { tab: AiConsoleTab; activeTab: AiConsoleTab; onClick: () => void }) {
  const Icon = tab === 'chat' ? MessageSquare : tab === 'agent' ? Bot : tab === 'model' ? Cpu : ServerCog
  return (
    <button className={activeTab === tab ? 'active' : ''} onClick={onClick} type="button" role="tab" aria-selected={activeTab === tab}>
      <Icon aria-hidden="true" />
      <span>{tabLabels[tab]}</span>
    </button>
  )
}

export function SearchField({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <label className="searchbox">
      <Search aria-hidden="true" />
      <input value={value} onChange={(event) => onChange(event.target.value)} placeholder="搜索资源..." />
    </label>
  )
}

export function CreateCard({ title, subtitle, onClick }: { title: string; subtitle: string; onClick: () => void }) {
  return (
    <button className="info-card create-card" type="button" onClick={onClick} aria-label={title}>
      <span className="plus-icon">
        <Plus aria-hidden="true" />
      </span>
      <span>
        <strong>{title}</strong>
        <small>{subtitle}</small>
      </span>
    </button>
  )
}

export function SessionCard({
  session,
  agent,
  onEdit,
  onDelete,
  deletePending,
}: {
  session: AgentSessionDTO
  agent?: AgentDefinitionDTO
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const navigate = useNavigate()
  const sessionLabel = session.title || session.sessionId
  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <MessageSquare aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{session.title || 'Untitled Chat'}</h3>
            <p>{agent?.name || session.agentName}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label="Status" value={session.status} />
        <MetaRow label="Agent" value={session.agentName} />
        <MetaRow label="Updated" value={formatBackendDate(session.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`进入会话 ${sessionLabel}`}
          onClick={() => navigate(`/agent/sessions/${session.sessionId}`)}
        >
          <ChevronRight aria-hidden="true" />
          进入会话
        </button>
        <button className="action-enter-btn" type="button" aria-label={`编辑 Chat ${sessionLabel}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
          编辑
        </button>
        <button className="action-enter-btn danger" type="button" aria-label={`删除 Chat ${sessionLabel}`} onClick={onDelete} disabled={deletePending}>
          <Trash2 aria-hidden="true" />
          删除
        </button>
      </div>
    </article>
  )
}

export function AgentResourceCard({
  agent,
  onStart,
  onEdit,
  onDelete,
  deletePending,
}: {
  agent: AgentDefinitionDTO
  onStart: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  return (
    <ResourceCard
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        ['Model', `${agent.defaultProviderName}/${agent.defaultModelName}`],
        ['Variant', agent.defaultVariant || '-'],
      ]}
      onStart={onStart}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}

export function ModelResourceCard({
  model,
  onEdit,
  onDelete,
  deletePending,
}: {
  model: AgentModelDTO
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  return (
    <ResourceCard
      icon="model"
      title={model.name}
      subtitle={model.description || `${model.providerName}/${model.name}`}
      rows={[
        ['Provider', model.providerName],
        ['Variant', model.defaultVariant || '-'],
        ['Variants', formatJsonSummary(model.variantsJson)],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}

export function ProviderResourceCard({
  provider,
  onEdit,
  onDelete,
  deletePending,
}: {
  provider: AgentProviderDTO
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  return (
    <ResourceCard
      icon="provider"
      title={provider.name}
      subtitle={provider.description || provider.baseUrl || provider.providerType}
      rows={[
        ['Type', provider.providerType],
        ['Base URL', provider.baseUrl || '-'],
        ['Timeout', provider.timeoutMillis ? `${provider.timeoutMillis} ms` : '-'],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}

function ResourceCard({
  icon,
  title,
  subtitle,
  rows,
  onStart,
  onEdit,
  onDelete,
  deletePending,
}: {
  icon: 'agent' | 'model' | 'provider'
  title: string
  subtitle: string
  rows: Array<[string, string]>
  onStart?: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const Icon = icon === 'agent' ? Bot : icon === 'model' ? Cpu : ServerCog
  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <Icon aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{title}</h3>
            <p>{subtitle}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        {rows.map(([label, value]) => (
          <MetaRow key={label} label={label} value={value} />
        ))}
      </div>
      <div className="chat-card-foot split">
        {onStart && (
          <button className="action-enter-btn green" type="button" aria-label={`创建会话 ${title}`} onClick={onStart}>
            <ChevronRight aria-hidden="true" />
            创建会话
          </button>
        )}
        <button className="action-enter-btn" type="button" aria-label={`编辑 ${title}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
          编辑
        </button>
        <button className="action-enter-btn danger" type="button" aria-label={`删除 ${title}`} onClick={onDelete} disabled={deletePending}>
          <Trash2 aria-hidden="true" />
          删除
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

export function StateBlock({ title, tone }: { title: string; tone?: 'danger' }) {
  return <div className={`state-block ${tone === 'danger' ? 'danger' : ''}`}>{title}</div>
}
