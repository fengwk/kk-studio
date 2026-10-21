import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChatRuntimeContext } from '@/features/chat/ChatRuntimeContext'
import { CreateChatDialog } from '@/app/ai-console/CreateChatDialog'
import { TaskToolRendererLazy } from '@/app/ai-console/TaskToolRendererLazy'
import type { ToolRendererMessage } from '@/platform/extensions/types'

vi.mock('@/features/chat/CreateChatModal', () => ({
  CreateChatModal: () => <div>lazy create chat modal</div>,
}))

vi.mock('@/features/chat/runtime/thread-panel/messages/TaskToolRenderer', () => ({
  TaskToolRenderer: () => <div>lazy task renderer</div>,
}))

describe('AI lazy contributions', () => {
  it('loads the create dialog only while the Chat controller reports it open', async () => {
    const closed = { createChatModal: { open: false } } as never
    const { rerender } = render(
      <ChatRuntimeContext.Provider value={closed}>
        <CreateChatDialog />
      </ChatRuntimeContext.Provider>,
    )
    expect(screen.queryByText('lazy create chat modal')).not.toBeInTheDocument()

    const open = { createChatModal: { open: true } } as never
    rerender(
      <ChatRuntimeContext.Provider value={open}>
        <CreateChatDialog />
      </ChatRuntimeContext.Provider>,
    )
    expect(await screen.findByText('lazy create chat modal')).toBeInTheDocument()
  })

  it('loads the task renderer through its extension wrapper', async () => {
    const message: ToolRendererMessage = {
      rendererKey: 'task',
      phase: 'result',
      contents: [{ type: 'text', text: '<task id="task-1" state="completed">done</task>' }],
      toolCallId: 'call-1',
      toolName: 'task',
      arguments: '{}',
      status: 'done',
    }

    render(<TaskToolRendererLazy message={message} expanded />)

    expect(await screen.findByText('lazy task renderer')).toBeInTheDocument()
  })
})
