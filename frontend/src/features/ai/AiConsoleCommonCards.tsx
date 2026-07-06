import { Bot, Cpu, MessageSquare, Plus, Search, ServerCog } from 'lucide-react'
import type { AiConsoleTab } from '@/features/ai/ai-console-types'
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

export function StateBlock({ title, tone }: { title: string; tone?: 'danger' }) {
  return <div className={`state-block ${tone === 'danger' ? 'danger' : ''}`}>{title}</div>
}
