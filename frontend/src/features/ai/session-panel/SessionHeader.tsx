import type { ReactNode } from 'react'

export function SessionHeader({
  title,
  subtitle,
  status,
}: {
  title: string
  subtitle?: string
  status?: ReactNode
}) {
  return (
    <header className="session-header">
      <div className="session-header-meta">
        <strong>{title}</strong>
        {subtitle ? <span>{subtitle}</span> : null}
      </div>
      {status ? <div className="session-header-status">{status}</div> : null}
    </header>
  )
}
