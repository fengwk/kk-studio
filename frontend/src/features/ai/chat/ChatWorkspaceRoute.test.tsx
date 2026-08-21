import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import ChatWorkspaceRoute from '@/features/ai/chat/ChatWorkspaceRoute'

vi.mock('@/features/ai/chat/ChatWorkspacePage', () => ({
  ChatWorkspacePage: () => <div>chat workspace page</div>,
}))

it('keeps the extension overlay inside the lazy Chat workspace route', () => {
  // RegisteredPage 把 OverlayHost 作为 children 传入；lazy route 必须原样保留该组合边界。
  render(
    <ChatWorkspaceRoute>
      <div>workspace overlay</div>
    </ChatWorkspaceRoute>,
  )

  expect(screen.getByText('chat workspace page')).toBeInTheDocument()
  expect(screen.getByText('workspace overlay')).toBeInTheDocument()
})
