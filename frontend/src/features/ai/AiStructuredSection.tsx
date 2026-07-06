import type { ReactNode } from 'react'

export function StructuredSection({
  label,
  actions,
  children,
}: {
  label: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="structured-section" aria-label={label}>
      <div className="structured-section-head">
        <strong>{label}</strong>
        {actions}
      </div>
      {children}
    </section>
  )
}
