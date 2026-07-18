import type { ReactNode } from 'react'

export function ThreadHeader({
  title,
  subtitle,
  status,
}: {
  title: string
  subtitle?: string
  status?: ReactNode
}) {
  return (
    <header className="thread-header">
      <div className="thread-header-meta">
        <strong>{title}</strong>
        {subtitle ? <span>{subtitle}</span> : null}
      </div>
      {status ? <div className="thread-header-status">{status}</div> : null}
    </header>
  )
}
