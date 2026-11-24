import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import type { ReactNode } from 'react'

// The workspace controller moves host elements to preserve the renderer and its draft.
const roots = new WeakMap<HTMLElement, Root>()

export function mountDomView(host: HTMLElement, view: ReactNode) {
  const root = roots.get(host)
  if (view === null) {
    if (root) {
      root.unmount()
      roots.delete(host)
    }
    return
  }
  const next = root ?? createRoot(host)
  roots.set(host, next)
  flushSync(() => next.render(view))
}
