import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'

const message: ToolDialogueMessage = {
  id: 'tool-1',
  role: 'tool',
  subjectEntryId: 'entry-1',
  createdAt: null,
  status: 'done',
  phase: 'result',
  text: 'full result',
  toolCallId: 'call-1',
  toolName: 'read',
  rendererKey: 'read',
  arguments: '{"path":"README.md"}',
  attachments: [],
}

describe('MessageList tool renderer dispatch', () => {
  it('dispatches by the frozen rendererKey and falls back when no contribution exists', () => {
    const host = new ExtensionHost()
    host.register({
      id: 'test.tools',
      toolRenderers: [
        {
          id: 'read',
          component: ({ message: rendered }) => (
            <div>read renderer: {rendered.arguments}</div>
          ),
        },
      ],
    })
    const { rerender } = render(
      <ExtensionHostProvider host={host}>
        <MessageList messages={[message]} />
      </ExtensionHostProvider>,
    )
    expect(screen.getByText('read renderer: {"path":"README.md"}')).toBeInTheDocument()
    expect(screen.queryByText('full result')).not.toBeInTheDocument()

    rerender(
      <ExtensionHostProvider host={host}>
        <MessageList messages={[{ ...message, rendererKey: 'unknown' }]} />
      </ExtensionHostProvider>,
    )
    expect(screen.getByText('full result')).toBeInTheDocument()
  })
})
