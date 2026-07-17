import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ChatToolMessage } from '@/features/ai/ChatToolMessage'

describe('ChatToolMessage', () => {
  it('keeps a non-media artifact available as an explicit download link', () => {
    render(
      <ChatToolMessage
        message={{
          id: 'tool-1',
          role: 'tool',
          runId: 'run-1',
          toolCallId: 'call-1',
          toolName: 'export',
          arguments: '{}',
          text: '',
          attachments: [
            {
              type: 'file',
              name: 'artifact-1',
              mime: 'application/octet-stream',
              data: '/api/artifacts/artifact-1',
            },
          ],
          createdAt: '2026-07-17T00:00:00',
          status: 'done',
        }}
      />,
    )

    expect(screen.getByText('[file] artifact-1')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开原始内容' })).toHaveAttribute(
      'href',
      '/api/artifacts/artifact-1',
    )
  })
})
