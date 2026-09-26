import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import { threadCommandsForTarget } from '@/features/ai/runtime/thread-panel/thread-commands'

describe('ThreadComposer /goal interactions', () => {
  it('pure /goal opens goal panel without sending messages or clearing composer parts', async () => {
    const user = userEvent.setup()
    const onPartsChange = vi.fn()
    const onSubmit = vi.fn()
    const onSubmitGoal = vi.fn()
    const onCommand = vi.fn()

    const textPart = createTextPart('/goal')
    const initialParts: ComposerPart[] = [textPart]

    render(
      <ThreadComposer
        parts={initialParts}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onSubmitGoal={onSubmitGoal}
        onCommand={onCommand}
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' }, { owner: { type: 'CHAT', id: 'c1' } })}
      />,
    )

    // Option for goal command is visible in slash palette
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    const goalOption = await screen.findByRole('option', { name: /^goal/ })
    expect(goalOption).toBeInTheDocument()

    // Clicking the goal command palette option
    await user.click(goalOption)

    // Opens goal panel via onCommand
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'goal' }))
    // Never calls normal onSubmit
    expect(onSubmit).not.toHaveBeenCalled()
    // Never calls onSubmitGoal
    expect(onSubmitGoal).not.toHaveBeenCalled()
    // Does NOT clear parts
    expect(onPartsChange).not.toHaveBeenCalled()
  })

  it('submitting pure /goal with attachment opens panel without sending message or clearing attachment', async () => {
    const user = userEvent.setup()
    const onPartsChange = vi.fn()
    const onSubmit = vi.fn()
    const onSubmitGoal = vi.fn()
    const onCommand = vi.fn()

    const attachment = createAttachmentPart('upload-123', 'test.png')
    const textPart = createTextPart('/goal')
    const initialParts: ComposerPart[] = [attachment, textPart]

    render(
      <ThreadComposer
        parts={initialParts}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onSubmitGoal={onSubmitGoal}
        onCommand={onCommand}
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' }, { owner: { type: 'CHAT', id: 'c1' } })}
      />,
    )

    const sendBtn = screen.getByRole('button', { name: '发送消息' })
    expect(sendBtn).toBeEnabled()

    await user.click(sendBtn)

    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'goal' }))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(onSubmitGoal).not.toHaveBeenCalled()
    expect(onPartsChange).not.toHaveBeenCalled()
  })

  it('inline /goal <objective> submits typed goal, keeps attachments, and does not send regular message', async () => {
    const user = userEvent.setup()
    const onPartsChange = vi.fn()
    const onSubmit = vi.fn()
    const onSubmitGoal = vi.fn()
    const onCommand = vi.fn()

    const attachment = createAttachmentPart('upload-123', 'test.png')
    const textPart = createTextPart('/goal Implement branch architecture')
    const initialParts: ComposerPart[] = [attachment, textPart]

    render(
      <ThreadComposer
        parts={initialParts}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onSubmitGoal={onSubmitGoal}
        onCommand={onCommand}
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' }, { owner: { type: 'CHAT', id: 'c1' } })}
      />,
    )

    const sendBtn = screen.getByRole('button', { name: '发送消息' })
    expect(sendBtn).toBeEnabled()

    await user.click(sendBtn)

    // Submits goal objective
    expect(onSubmitGoal).toHaveBeenCalledWith(
      'Implement branch architecture',
      expect.arrayContaining([attachment, textPart]),
    )
    // Regular submit is NOT called
    expect(onSubmit).not.toHaveBeenCalled()
    // Composer draft is updated to keep remaining non-text parts (the attachment)
    expect(onPartsChange).toHaveBeenCalledWith([attachment])
  })

  it('inline /goal rejects objectives longer than 2000 code points', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onSubmitGoal = vi.fn()

    const longObjective = '🎯'.repeat(2001)
    const textPart = createTextPart(`/goal ${longObjective}`)

    render(
      <ThreadComposer
        parts={[textPart]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={onSubmit}
        onSubmitGoal={onSubmitGoal}
        onCommand={vi.fn()}
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' }, { owner: { type: 'CHAT', id: 'c1' } })}
      />,
    )

    const sendBtn = screen.getByRole('button', { name: '发送消息' })
    await user.click(sendBtn)

    expect(onSubmitGoal).not.toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(/2000/)).toBeInTheDocument()
  })
})
