import type { ReactNode } from 'react'

/** Compact status strip under the composer (pi footer role). */
export function SessionFooter({ children }: { children?: ReactNode }) {
  if (!children) {
    return null
  }
  return (
    <footer className="session-footer" aria-label="会话状态">
      {children}
    </footer>
  )
}
