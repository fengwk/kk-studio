import { Plus, Search } from 'lucide-react'

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
      <span className="plus-icon"><Plus aria-hidden="true" /></span>
      <span><strong>{title}</strong><small>{subtitle}</small></span>
    </button>
  )
}

export function StateBlock({ title, tone }: { title: string; tone?: 'danger' }) {
  return <div className={`state-block ${tone === 'danger' ? 'danger' : ''}`}>{title}</div>
}
