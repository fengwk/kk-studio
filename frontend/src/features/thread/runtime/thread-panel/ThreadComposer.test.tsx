import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ThreadComposer,
} from '@/features/thread/runtime/thread-panel/ThreadComposer'
import {
  createAttachmentPart,
  createTextPart,
  slashQueryOf,
  type ComposerPart,
} from '@/features/thread/composer/composer-parts'
import {
  filterThreadCommands,
  threadCommandsForTarget,
  THREAD_COMMANDS,
  type ThreadCommand,
} from '@/features/thread/runtime/thread-panel/thread-commands'
import { firstEnabledCommandIndex } from '@/features/thread/runtime/thread-panel/thread-command-navigation'
import { storeUnknownUploads } from '@/features/thread/composer/composer-draft'
import { partsToMessageContents, type StorageService } from '@/features/thread/composer'
import { ThreadCommandPalette } from '@/features/thread/runtime/thread-panel/ThreadCommandPalette'
import { ThreadWidgetStack } from '@/features/thread/runtime/thread-panel/ThreadWidgetStack'
import type { ThreadComposerSettingsInput } from '@/features/thread/runtime/thread-panel/ThreadComposerControls'
import type { HashFile } from '@/shared/lib/upload-file'
import { AttachmentStrip } from '@/features/thread/composer/attachment-strip'
import type { AttachmentUpload } from '@/features/thread/composer/use-attachment-uploads'
import { fakeStorage, fileOf, partsSnapshot, waitForIdleUploads } from '@/test-support/composer-fixtures'

function ControlledComposer() {
  const [parts, setParts] = useState<ComposerPart[]>([])
  return (
    <ThreadComposer
      parts={parts}
      pending={false}
      disabled={false}
      onPartsChange={setParts}
      onSubmit={vi.fn()}
      onCommand={vi.fn()}
    />
  )
}

function InteractionComposer({
  onSubmit,
  onCommand,
  initial = [],
  historicalUserMessages = [],
  queuedUserMessages = [],
  focusOnEscape = false,
  settings,
}: {
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  initial?: ComposerPart[]
  historicalUserMessages?: readonly string[]
  queuedUserMessages?: readonly string[]
  focusOnEscape?: boolean
  settings?: ThreadComposerSettingsInput
}) {
  const [parts, setParts] = useState(initial)
  return (
    <ThreadComposer
      parts={parts}
      pending={false}
      disabled={false}
      onPartsChange={setParts}
      onSubmit={onSubmit}
      onCommand={onCommand}
      historicalUserMessages={historicalUserMessages}
      queuedUserMessages={queuedUserMessages}
      focusOnEscape={focusOnEscape}
      settings={settings}
    />
  )
}

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
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })}
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
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })}
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
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })}
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
        commands={threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })}
      />,
    )

    const sendBtn = screen.getByRole('button', { name: '发送消息' })
    await user.click(sendBtn)

    expect(onSubmitGoal).not.toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(/2000/)).toBeInTheDocument()
  })
})

describe('ThreadComposer and commands', () => {
  it('keeps stable command order and grays unsupported blank-scene commands', () => {
    expect(THREAD_COMMANDS.map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'yolo',
      'models',
      'history',
      'stop',
      'new',
      'upload',
      'debug',
      'subagent',
      'shortcuts',
      'compact',
      'rename-session',
      'rename-thread',
      'goal',
      'files',
      'shell',
    ])
    // `/session`（全局 Session 重绑定）已彻底移除，不再出现在稳定命令表中。
    expect(THREAD_COMMANDS.map((command) => command.id)).not.toContain('session')
    // 命令可用性只由目标 kind 与显式能力决定，不再接受 owner 选项。
    const blank = threadCommandsForTarget({ kind: 'NEW_SESSION_DRAFT' })
    expect(blank.map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    // 空面板还没有 Thread，因此 `/history`/`/stop`/`/new`/`/debug`/`/compact`
    // 不可用；面板切换、快捷键以及文件/终端入口保持可用。
    expect(blank.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'yolo',
      'models',
      'upload',
      'shortcuts',
      'files',
      'shell',
    ])
    expect(blank.find((c) => c.id === 'new')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'history')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'debug')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'subagent')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'compact')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'rename-session')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'rename-thread')?.disabled).toBe(true)
    expect(threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 'thread-1' }).every((c) => !c.disabled)).toBe(true)
    expect(filterThreadCommands('yo').map((c) => c.id)).toEqual(['yolo'])
    expect(filterThreadCommands('sto').map((c) => c.id)).toEqual(['stop', 'history'])
    expect(filterThreadCommands('hist')[0]?.id).toBe('history')
    expect(filterThreadCommands('debug')[0]?.id).toBe('debug')
    expect(filterThreadCommands('shortcuts')[0]?.id).toBe('shortcuts')
    expect(['history', 'branch'].every((keyword) =>
      filterThreadCommands(keyword).some((command) => command.id === 'history'))).toBe(true)
    expect(filterThreadCommands('', blank).map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    expect(firstEnabledCommandIndex(blank)).toBe(0)
    expect(filterThreadCommands('missing')).toEqual([])
  })

  it('exposes navigation and goal commands for bound threads without owner gating', () => {
    // 既有 Thread 的命令可用性由目标 kind 与显式能力选项决定，owner 不参与裁剪。
    const bound = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })
    expect(bound.find((command) => command.id === 'thread')?.disabled).toBe(false)
    expect(bound.find((command) => command.id === 'new')?.disabled).toBe(false)
    expect(bound.find((command) => command.id === 'goal')?.disabled).toBe(false)

    // 显式能力选项仍然生效：禁止分支时导航命令被禁用，goal 不受影响。
    const noBranching = threadCommandsForTarget(
      { kind: 'BOUND_THREAD', threadId: 't1' },
      { allowBranching: false },
    )
    expect(noBranching.find((command) => command.id === 'thread')?.disabled).toBe(true)
    expect(noBranching.find((command) => command.id === 'new')?.disabled).toBe(true)
    expect(noBranching.find((command) => command.id === 'goal')?.disabled).toBe(false)
  })

  it('uses slash as a text-only shortcut without consuming attachments', () => {
    expect(slashQueryOf([createTextPart('/stop')])).toBe('stop')
    expect(
      slashQueryOf([
        createTextPart('/stop'),
        createAttachmentPart('upload-1', 'keep.txt'),
      ]),
    ).toBeNull()
  })

  it('allows send and executes slash commands', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCommand = vi.fn()
    const onPartsChange = vi.fn()
    const { rerender } = render(
      <ThreadComposer
        parts={[createTextPart('hello')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalled()

    rerender(
      <ThreadComposer
        parts={[createTextPart('/stop')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))
    expect(onPartsChange).toHaveBeenCalledWith([])

    onCommand.mockClear()
    rerender(
      <ThreadComposer
        parts={[createTextPart('/history')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'history' }))
  })

  it('opens the primary command menu from plus without writing slash into the editor', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer />)
    const add = screen.getByRole('button', { name: '打开命令表' })
    const editor = screen.getByLabelText('给 AI 发送消息')
    expect(add).toHaveAttribute('aria-expanded', 'false')

    await user.click(add)

    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(add).toHaveAttribute('aria-expanded', 'true')
    expect(editor).toBeEmptyDOMElement()
    expect(document.querySelector('.thread-command-search')).toBeNull()
    expect(screen.getByRole('option', { name: /^upload/ })).toBeInTheDocument()

    await user.click(editor)
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(editor).toBeEmptyDOMElement()
  })

  it('opens plus menu over a non-empty draft and executes commands without clearing it', async () => {
    const user = userEvent.setup()
    const onPartsChange = vi.fn()
    const onCommand = vi.fn()
    render(
      <ThreadComposer
        parts={[createTextPart('保留这段草稿')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={vi.fn()}
        onCommand={onCommand}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')

    await user.click(screen.getByRole('button', { name: '打开命令表' }))

    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(editor).toHaveTextContent('保留这段草稿')
    expect(onPartsChange).not.toHaveBeenCalled()

    await user.click(screen.getByRole('option', { name: /^agent/ }))

    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'agent' }))
    expect(onPartsChange).not.toHaveBeenCalled()
    expect(editor).toHaveTextContent('保留这段草稿')
  })

  it('handles /upload locally and keeps the native file input hidden', async () => {
    const user = userEvent.setup()
    render(
      <ThreadComposer
        parts={[createTextPart('/upload')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')
    expect(input).not.toBeNull()
    expect(input).toHaveAttribute('hidden')
    expect(input).toHaveClass('composer-file-input-hidden')
    const click = vi.spyOn(input!, 'click').mockImplementation(() => undefined)
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.click(await screen.findByRole('option', { name: /^upload/ }))
    expect(click).toHaveBeenCalledOnce()
    click.mockRestore()
  })

  it('blocks send when draft is blank or pending', () => {
    const { rerender } = render(
      <ThreadComposer
        parts={[createTextPart('   ')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    rerender(
      <ThreadComposer
        parts={[createTextPart('/yolo')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
  })

  it('keeps the placeholder visible for newline-only and whitespace-only drafts', () => {
    const props = {
      pending: false,
      disabled: false,
      onPartsChange: vi.fn(),
      onSubmit: vi.fn(),
      onCommand: vi.fn(),
    }
    const { rerender } = render(<ThreadComposer {...props} parts={[]} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    rerender(<ThreadComposer {...props} parts={[createTextPart('\n')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    rerender(<ThreadComposer {...props} parts={[createTextPart(' \n ')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    rerender(<ThreadComposer {...props} parts={[createTextPart('message')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'false')
  })

  it('renders a two-level composer with Permission and Model/Variant controls', () => {
    const { container } = render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
        settings={{
          modelSelection: { modelName: 'MiniMax', variant: 'high' },
          models: [
            {
              kind: 'MODEL',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'high' }],
              },
            },
          ],
          yoloEnabled: false,
          environmentName: null,
          environments: [],
          onModelChange: vi.fn(),
          onYoloChange: vi.fn(),
          onEnvironmentChange: vi.fn(),
        }}
      />,
    )

    const dock = container.querySelector('.thread-dock')
    expect(dock).not.toBeNull()
    expect(dock?.querySelector('.composer-editor')).not.toBeNull()
    expect(dock?.querySelector('.thread-dock-controls')).not.toBeNull()
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('Default')
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toHaveTextContent(
      'MiniMax (high)',
    )
  })

  it('selects Permission and a two-level Model/Variant through anchored listboxes', async () => {
    const user = userEvent.setup()
    const onModelChange = vi.fn()
    const onYoloChange = vi.fn()
    render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
        settings={{
          modelSelection: { modelName: 'MiniMax', variant: 'default' },
          models: [
            {
              kind: 'MODEL',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'high' }],
              },
            },
            {
              kind: 'MODEL',
              name: 'gpt',
              config: {
                defaultVariant: 'fast',
                variants: [{ id: 'fast' }, { id: 'quality' }],
              },
            },
          ],
          yoloEnabled: false,
          environmentName: null,
          environments: [],
          onModelChange,
          onYoloChange,
          onEnvironmentChange: vi.fn(),
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: '权限模式' }))
    await user.click(screen.getByRole('option', { name: 'YOLO' }))
    expect(onYoloChange).toHaveBeenCalledWith(true)

    await user.click(screen.getByRole('button', { name: 'Model 与 Variant' }))
    const search = screen.getByRole('searchbox', { name: '搜索模型' })
    expect(search).toHaveFocus()
    await user.type(search, 'gpt')
    expect(screen.queryByRole('option', { name: 'MiniMax' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'gpt' }))
    expect(screen.getByRole('listbox', { name: 'Variant 选项' })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'quality' }))
    expect(onModelChange).toHaveBeenCalledWith({
            modelName: 'gpt',
      variant: 'quality',
    })
  })

  it('opens the model menu from /models', async () => {
    const user = userEvent.setup()
    render(
      <ThreadComposer
        parts={[createTextPart('/models')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
        settings={{
          modelSelection: { modelName: 'MiniMax', variant: 'default' },
          models: [
            {
              kind: 'MODEL',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }],
              },
            },
          ],
          yoloEnabled: false,
          environmentName: null,
          environments: [],
          onModelChange: vi.fn(),
          onYoloChange: vi.fn(),
          onEnvironmentChange: vi.fn(),
        }}
      />,
    )
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^models/ }))
    expect(screen.getByRole('searchbox', { name: '搜索模型' })).toHaveFocus()
    expect(screen.getByRole('listbox', { name: 'Model 选项' })).toBeInTheDocument()
  })

  describe('ThreadComposer attachment draft recovery', () => {
    it('restores complete_unknown attachment on reload and retries complete with same uploadId via retry button', async () => {
      // 测试意图：验证 reload 后未知 complete 附件展示重试按钮，点击真实按钮能触发同一 uploadId 重新 complete
      const scope = 'test-composer-scope'
      const localId = 'loc-unknown-1'
      const uploadId = 'upload-id-same-123'
      storeUnknownUploads(scope, [
        {
          localId,
          uploadId,
          filename: 'ambiguous.png',
          mediaType: 'image/png',
          sizeBytes: 2048,
          sha256: 'sha-same',
        },
      ])

      const fakeStorage = {
        reserveUpload: vi.fn(),
        completeUpload: vi.fn().mockResolvedValue({
          id: uploadId,
          blobId: 'blob-123',
          mediaKind: 'image',
          state: 'READY',
          presignedPut: { url: 'put', method: 'PUT', headers: {} },
          filename: 'ambiguous.png',
          mediaType: 'image/png',
          sizeBytes: 2048,
          sha256: 'sha-same',
          expiresAt: '2026',
          createTime: '2026',
        }),
        deleteUpload: vi.fn().mockResolvedValue(undefined),
        uploadFile: vi.fn().mockResolvedValue(undefined),
        getBlobDownloadUrl: vi.fn(),
        getBlobPreviewUrl: vi.fn(),
      }

      const user = userEvent.setup()
      const draftPart = createAttachmentPart(localId, 'ambiguous.png')
      render(
        <ThreadComposer
          parts={[draftPart]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
          scope={scope}
          storageService={fakeStorage as unknown as StorageService}
        />,
      )

      expect(screen.getAllByText('[ambiguous.png]').length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText('状态未知，可重试')).toBeInTheDocument()

      const retryBtn = screen.getByRole('button', { name: '重试完成 ambiguous.png' })
      expect(retryBtn).toBeInTheDocument()

      await user.click(retryBtn)

      expect(fakeStorage.completeUpload).toHaveBeenCalledWith('upload-id-same-123')
      expect(await screen.findByText('2 KB')).toBeInTheDocument()
    })

    it('fails closed in ThreadComposer when Storage quota is exceeded, calling completeUpload 0 times', async () => {
      // 测试意图：ThreadComposer 中当本地 Storage 满额时，发送前持久化失败阻断 completeUpload 发起（调用 0 次）
      const scope = 'quota-composer-scope'
      const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('QuotaExceededError', 'QuotaExceededError')
      })

      const fakeStorage = {
        reserveUpload: vi.fn().mockResolvedValue({
          id: 'up-quota-1',
          blobId: 'blob-quota',
          mediaKind: 'image',
          state: 'PENDING',
          presignedPut: { url: 'put', method: 'PUT', headers: {} },
          filename: 'quota.png',
          mediaType: 'image/png',
          sizeBytes: 1024,
          sha256: 'sha-quota',
          expiresAt: '2026',
          createTime: '2026',
        }),
        completeUpload: vi.fn(),
        deleteUpload: vi.fn().mockResolvedValue(undefined),
        uploadFile: vi.fn().mockResolvedValue(undefined),
        getBlobDownloadUrl: vi.fn(),
        getBlobPreviewUrl: vi.fn(),
      }

      render(
        <ThreadComposer
          parts={[]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
          scope={scope}
          storageService={fakeStorage as unknown as StorageService}
          hashFile={async () => 'sha-quota'}
        />,
      )

      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      expect(fileInput).toBeInTheDocument()

      const file = new File(['bytes'], 'quota.png', { type: 'image/png' })
      await userEvent.upload(fileInput, file)

      // 等待 reserveUpload 发生
      await vi.waitFor(() => expect(fakeStorage.reserveUpload).toHaveBeenCalled())

      // 核心验证：因 Storage 写入抛错，completeUpload 绝对没有被调用（0 次）
      expect(fakeStorage.completeUpload).toHaveBeenCalledTimes(0)
      // 预留的 handle 已被安全回滚释放
      expect(fakeStorage.deleteUpload).toHaveBeenCalledWith('up-quota-1')

      setItemSpy.mockRestore()
    })

    it('does not re-restore duplicate items when parts change', () => {
      // 测试意图：验证当输入框 parts 频繁变化时，不会重复从 scope 恢复导致 Strip 出现重复附件
      const scope = 'scope-dedup-check'
      storeUnknownUploads(scope, [
        {
          localId: 'loc-single',
          uploadId: 'up-single',
          filename: 'single.png',
          mediaType: 'image/png',
          sizeBytes: 1024,
          sha256: 'sha-s',
        },
      ])

      const { rerender } = render(
        <ThreadComposer
          parts={[createAttachmentPart('loc-single', 'single.png')]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
          scope={scope}
        />,
      )

      expect(screen.getAllByText('[single.png]').length).toBeGreaterThanOrEqual(1)

      // 改变 parts 输入内容（模拟输入文字）
      rerender(
        <ThreadComposer
          parts={[
            createAttachmentPart('loc-single', 'single.png'),
            createTextPart('hello there'),
          ]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
          scope={scope}
        />,
      )

      // strip 中该附件仍然只有一个 listitem，绝不重复生成
      const retryButtons = screen.queryAllByRole('button', { name: /重试完成 single.png/ })
      expect(retryButtons).toHaveLength(1)
    })
  })
})
describe('ThreadComposer interactions', () => {
  // Esc 切换焦点并保留草稿；blur 收起后隐藏状态不执行命令也不发送普通消息；再次 Esc 聚焦后自动重开菜单。
  it('preserves slash text across repeated Escape and reopens after editing', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCommand = vi.fn()
    render(<InteractionComposer focusOnEscape onSubmit={onSubmit} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/de')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    // 第 1 次 Esc：聚焦时 blur 并收起菜单，保留草稿 /de
    await user.keyboard('{Escape}')
    expect(editor).toHaveTextContent('/de')
    expect(editor).not.toHaveFocus()
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()

    // 失焦状态下按 Enter：无隐藏命令执行，也不发送普通消息
    await user.keyboard('{Enter}')
    expect(onSubmit).not.toHaveBeenCalled()
    expect(onCommand).not.toHaveBeenCalled()
    expect(editor).toHaveTextContent('/de')

    // 第 2 次 Esc：未聚焦时通过 focusOnEscape 重新聚焦 editor，/de 自动重开菜单
    await user.keyboard('{Escape}')
    expect(editor).toHaveFocus()
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(editor).toHaveTextContent('/de')

    // 继续输入补全并执行可见选项
    await user.keyboard('bug')
    expect(editor).toHaveTextContent('/debug')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ id: 'debug' }))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(editor).toBeEmptyDOMElement()
  })

  // + 菜单覆盖 slash 提示时，一次 Esc 必须关闭二者，不能退回自动弹出的 slash 菜单。
  it('closes plus and slash menus without losing text or attachments', async () => {
    const user = userEvent.setup()
    render(<InteractionComposer
      initial={[createTextPart('/de'), createAttachmentPart('upload-1', 'a.png')]}
      focusOnEscape
      onSubmit={vi.fn()}
      onCommand={vi.fn()}
    />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    // 含附件时保持原有 slash 识别规则；菜单关闭不得删除任意内容块。
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    const before = editor.textContent
    fireEvent.keyDown(window, { key: 'Escape' })
    await user.keyboard('{Escape}')
    expect(editor.textContent).toBe(before)
    expect(editor.querySelector('[data-part-type="attachment"]')).not.toBeNull()
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
  })

  // 隐藏提示不会解禁命令，也不能执行上一次活动选项。
  it('does not execute a disabled complete command after dismissal', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    const onSubmit = vi.fn()
    render(<ThreadComposer
      parts={[createTextPart('/debug')]}
      commands={[{ id: 'debug', label: 'debug', description: '', disabled: true }]}
      pending={false}
      disabled={false}
      onPartsChange={vi.fn()}
      onSubmit={onSubmit}
      onCommand={onCommand}
    />)
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('{Escape}{Enter}')
    expect(onCommand).not.toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByLabelText('给 AI 发送消息')).toHaveTextContent('/debug')
  })

  it('supports slash mode command execution and Escape close', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<InteractionComposer onSubmit={vi.fn()} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/yolo')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'yolo' }))
  })

  it('navigates slash options with ArrowUp/ArrowDown then confirms with Enter', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<InteractionComposer onSubmit={vi.fn()} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    // 稳定产品顺序保持不变：thread -> agent -> environment；初始 active 是第一项。
    await user.keyboard('{ArrowDown}{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'agent' }))
  })

  it('navigates historical users, queued users, then restores the current draft', async () => {
    const user = userEvent.setup()
    render(
      <InteractionComposer
        initial={[createTextPart('current draft')]}
        historicalUserMessages={['history 1', 'history 2']}
        queuedUserMessages={['queued 1', 'queued 2']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)

    // 游标从末尾草稿槽位开始，向上按时间倒序遍历三个数据源。
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('queued 2')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('queued 1')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history 2')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history 1')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history 1')

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}')
    expect(editor.textContent).toBe('current draft')
    await user.keyboard('{ArrowDown}')
    expect(editor.textContent).toBe('current draft')
  })

  it('promotes an edited recalled message to the new draft slot', async () => {
    const user = userEvent.setup()
    render(
      <InteractionComposer
        initial={[createTextPart('original draft')]}
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)
    await user.keyboard('{ArrowUp}')
    await user.type(editor, ' edited')

    // 编辑会结束浏览；再次向上取最近历史，向下则返回刚编辑出的新草稿。
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history')
    await user.keyboard('{ArrowDown}')
    expect(editor.textContent).toBe('history edited')
  })

  it('uses an empty virtual draft slot and does not discard attachment drafts', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <InteractionComposer
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history')
    await user.keyboard('{ArrowDown}')
    expect(editor.textContent).toBe('')

    rerender(
      <InteractionComposer
        key="attachment"
        initial={[
          createTextPart('keep attachment'),
          createAttachmentPart('upload-1', 'a.txt'),
        ]}
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const attachmentEditor = screen.getByLabelText('给 AI 发送消息')
    await user.click(attachmentEditor)
    await user.keyboard('{ArrowUp}')
    expect(attachmentEditor).toHaveTextContent('keep attachment')
    expect(attachmentEditor.querySelector('[data-upload-id="upload-1"]')).not.toBeNull()
  })

  it('keeps multiline caret navigation until it reaches the history boundary', () => {
    render(
      <InteractionComposer
        initial={[createTextPart('line 1\nline 2')]}
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    editor.focus()
    const selection = document.getSelection()
    const range = document.createRange()
    range.selectNodeContents(editor)
    range.collapse(false)
    selection?.removeAllRanges()
    selection?.addRange(range)

    // 第二行上的 ArrowUp 仍由 contenteditable 处理，不会误召回历史。
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('line 1\nline 2')

    range.selectNodeContents(editor)
    range.collapse(true)
    selection?.removeAllRanges()
    selection?.addRange(range)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('history')
  })

  it('scrolls a recalled multiline message to its ending caret', () => {
    render(
      <InteractionComposer
        initial={[createTextPart('current draft')]}
        historicalUserMessages={['line 1\nline 2\nline 3']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    Object.defineProperty(editor, 'scrollHeight', {
      configurable: true,
      value: 480,
    })
    editor.scrollTop = 0
    editor.focus()
    const selection = document.getSelection()
    const range = document.createRange()
    range.selectNodeContents(editor)
    range.collapse(false)
    selection?.removeAllRanges()
    selection?.addRange(range)

    // 历史内容超过 editor 高度时，末尾光标与内部滚动位置必须同步。
    fireEvent.keyDown(editor, { key: 'ArrowUp' })

    expect(editor.textContent).toBe('line 1\nline 2\nline 3')
    expect(editor.scrollTop).toBe(480)
  })

  it('opens from slash or plus and closes with Escape', async () => {
    const user = userEvent.setup()
    render(<InteractionComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const add = screen.getByRole('button', { name: '打开命令表' })
    await user.click(add)
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('给 AI 发送消息'), '/stop')
    const palette = await screen.findByLabelText('命令表')
    expect(palette).toBeInTheDocument()
    // Slash 查询只存在于 Composer；命令表不再重复渲染搜索标题行。
    expect(document.querySelector('.thread-command-search')).toBeNull()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })

  it('focuses only the enabled Composer when Escape is pressed outside the editor', async () => {
    const user = userEvent.setup()
    render(
      <>
        <button type="button">Transcript action</button>
        <InteractionComposer onSubmit={vi.fn()} onCommand={vi.fn()} />
        <InteractionComposer focusOnEscape onSubmit={vi.fn()} onCommand={vi.fn()} />
      </>,
    )
    const outside = screen.getByRole('button', { name: 'Transcript action' })
    const editors = screen.getAllByLabelText('给 AI 发送消息')
    await user.click(outside)
    expect(outside).toHaveFocus()

    await user.keyboard('{Escape}')

    // 多 Composer 页面只能由当前交互作用域接管 Escape，避免多个输入框争抢焦点。
    await waitFor(() => expect(editors[1]).toHaveFocus())
    expect(editors[0]).not.toHaveFocus()
    const selection = document.getSelection()
    expect(selection?.rangeCount).toBe(1)
    expect(editors[1]?.contains(selection?.getRangeAt(0).commonAncestorContainer ?? null)).toBe(true)
    const caret = selection?.getRangeAt(0)
    const trailing = document.createRange()
    trailing.selectNodeContents(editors[1]!)
    trailing.setStart(caret!.endContainer, caret!.endOffset)
    expect(caret?.collapsed).toBe(true)
    expect(trailing.toString()).toBe('')
  })

  it('does not steal Escape while a modal overlay is open', async () => {
    const user = userEvent.setup()
    render(
      <>
        <InteractionComposer focusOnEscape onSubmit={vi.fn()} onCommand={vi.fn()} />
        <div className="modal-backdrop">
          <button type="button">Modal action</button>
        </div>
      </>,
    )
    const modalAction = screen.getByRole('button', { name: 'Modal action' })
    await user.click(modalAction)
    await user.keyboard('{Escape}')

    // Overlay 拥有优先 Escape 语义，Composer 不得把焦点从弹层控件后方抢走。
    expect(modalAction).toHaveFocus()
    expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
  })

  it('does not consume Escape while the Composer is disabled', async () => {
    const user = userEvent.setup()
    render(
      <>
        <button type="button">Disabled scope action</button>
        <ThreadComposer
          parts={[]}
          pending
          disabled
          focusOnEscape
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
        />
      </>,
    )
    const outside = screen.getByRole('button', { name: 'Disabled scope action' })
    await user.click(outside)
    await user.keyboard('{Escape}')

    // 不可编辑时没有可恢复的输入目标，Escape 应留给当前控件/浏览器处理。
    expect(outside).toHaveFocus()
    expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
  })

  it('keeps the Composer mounted and restores its ending caret after an interaction panel closes', async () => {
    const props = {
      parts: [createTextPart('preserved draft')],
      pending: false,
      disabled: false,
      focusOnEscape: true,
      onPartsChange: vi.fn(),
      onSubmit: vi.fn(),
      onCommand: vi.fn(),
    }
    const { container, rerender } = render(<ThreadComposer {...props} active />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    rerender(<ThreadComposer {...props} active={false} />)
    expect(container.querySelector('.thread-composer')).toHaveAttribute('hidden')
    expect(editor).toHaveTextContent('preserved draft')

    rerender(<ThreadComposer {...props} active />)
    await waitFor(() => expect(editor).toHaveFocus())
    // 隐藏只切换交互槽位，不卸载草稿；回来后直接从末尾继续输入。
    expect(editor).toHaveTextContent('preserved draft')
    const selection = document.getSelection()
    const caret = selection?.getRangeAt(0)
    const trailing = document.createRange()
    trailing.selectNodeContents(editor)
    trailing.setStart(caret!.endContainer, caret!.endOffset)
    expect(trailing.toString()).toBe('')
  })

  it('keeps send enabled while a previous mutation is still pending', () => {
    render(
      <ThreadComposer
        parts={[createTextPart('hello')]}
        pending
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
  })

  it('cancels delayed focus work when the composer unmounts', () => {
    vi.useFakeTimers()
    try {
      const { unmount } = render(
        <ThreadComposer
          parts={[createTextPart('hello')]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
        />,
      )

      fireEvent.click(screen.getByRole('button', { name: '发送消息' }))
      expect(vi.getTimerCount()).toBe(1)
      unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders only the shared plus command entry, without standalone actor-state, Stop, or Retry controls', () => {
    render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '打开命令表' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.queryByText('RUNNING')).not.toBeInTheDocument()
    expect(screen.queryByText('IDLE')).not.toBeInTheDocument()
  })

  it('disables the complete composer while no Thread projection is available', () => {
    render(
      <ThreadComposer
        parts={[createTextPart('message')]}
        pending={false}
        disabled
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('给 AI 发送消息')).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '打开命令表' })).toBeDisabled()
  })

  it('handles true sequential Escape shortcuts with control menu priority: 1st closes control menu, 2nd blurs composer, 3rd refocuses editor via focusOnEscape', async () => {
    const user = userEvent.setup()
    const dummySettings: ThreadComposerSettingsInput = {
      modelSelection: { modelName: 'MiniMax', variant: 'default' },
      models: [{ kind: 'MODEL', name: 'MiniMax', config: { defaultVariant: 'default', variants: [{ id: 'default' }] } }],
      yoloEnabled: false,
      environmentName: null,
      environments: [],
      onModelChange: vi.fn(),
      onYoloChange: vi.fn(),
      onEnvironmentChange: vi.fn(),
    }
    render(<InteractionComposer focusOnEscape settings={dummySettings} onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    // 聚焦输入 /th
    await user.type(editor, '/th')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    // 打开权限菜单（上层 control menu）
    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()

    // 真正连续快捷键 1：Esc 关闭权限菜单，优先消费 Esc，保持 editor 聚焦并呈现 /th 命令表
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()
    expect(editor).toHaveFocus()
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()

    // 真正连续快捷键 2：Esc 此时聚焦且无上层菜单，blur 整个 composer 并收起
    await user.keyboard('{Escape}')
    expect(editor).not.toHaveFocus()
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(editor).toHaveTextContent('/th')

    // 真正连续快捷键 3：Esc 未聚焦时全局 focusOnEscape 恢复焦点，并因 /th 自动重开命令表
    await user.keyboard('{Escape}')
    await waitFor(() => expect(editor).toHaveFocus())
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(editor).toHaveTextContent('/th')
  })

  it('switches focus via Tab within composer region without closing the command palette', async () => {
    const user = userEvent.setup()
    render(<InteractionComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/th')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    // Tab 移动焦点到 + 按钮（仍在区域内）
    await user.tab()
    expect(screen.getByRole('button', { name: '打开命令表' })).toHaveFocus()
    // 菜单依然保持打开！
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()
  })

  it('toggles focus on Escape for plain text and preserves text content without opening menu', async () => {
    const user = userEvent.setup()
    render(<InteractionComposer focusOnEscape onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, 'ordinary text')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(editor).toHaveFocus()

    // 聚焦时 Esc：blur 失焦，普通文本保留
    await user.keyboard('{Escape}')
    expect(editor).not.toHaveFocus()
    expect(editor).toHaveTextContent('ordinary text')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()

    // 未聚焦时 Esc：focus 聚焦，文本保留，仍无菜单
    await user.keyboard('{Escape}')
    await waitFor(() => expect(editor).toHaveFocus())
    expect(editor).toHaveTextContent('ordinary text')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })
})

describe('ThreadCommandPalette', () => {
  it('filters and selects a slash command', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <ThreadCommandPalette
        open
        query="stop"
        activeIndex={0}
        onActiveIndexChange={vi.fn()}
        onSelect={onSelect}
      />,
    )
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))
  })
})

describe('ThreadWidgetStack', () => {
  it('renders queued messages under the working zone and stays empty when idle', () => {
    const { container, rerender } = render(
      <ThreadWidgetStack
        working
        queuedMessages={[
          { idempotencyKey: 'queued-1', role: 'user', text: '稍后处理这条', sequence: '1' },
        ]}
      />,
    )
    expect(screen.getByText('执行中')).toBeInTheDocument()
    expect(screen.getByText('稍后处理这条')).toBeInTheDocument()
    const zone = screen.getByLabelText('会话活动')
    const zoneText = zone.textContent ?? ''
    expect(zoneText.indexOf('执行中')).toBeLessThan(zoneText.indexOf('稍后处理这条'))

    rerender(<ThreadWidgetStack working={false} />)
    expect(container.querySelector('.thread-widget-zone')?.childNodes.length ?? 0).toBe(0)
  })
})

const EMPTY_PARTS: ComposerPart[] = []

const hashFile: HashFile = vi.fn(async () => 'a'.repeat(64))

const MODEL_SETTINGS: ThreadComposerSettingsInput = {
  modelSelection: { modelName: 'MiniMax', variant: 'default' },
  models: [
    {
      kind: 'MODEL',
      name: 'MiniMax',
      config: {
        defaultVariant: 'default',
        variants: [{ id: 'default' }, { id: 'high' }],
      },
    },
  ],
  yoloEnabled: false,
  environmentName: null,
  environments: [],
  onModelChange: vi.fn(),
  onYoloChange: vi.fn(),
  onEnvironmentChange: vi.fn(),
}

/** 受控 Harness：把 parts 状态回显到 DOM，便于精确断言草稿内容。 */
function Harness({
  service,
  initialParts = EMPTY_PARTS,
  onSubmit = vi.fn(),
  onCommand = vi.fn(),
  disabled = false,
  historicalUserMessages = [],
  focusOnEscape = false,
  settings,
  commands,
}: {
  service?: StorageService
  initialParts?: ComposerPart[]
  onSubmit?: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  onCommand?: (command: ThreadCommand) => void
  disabled?: boolean
  historicalUserMessages?: readonly string[]
  focusOnEscape?: boolean
  settings?: ThreadComposerSettingsInput
  commands?: ThreadCommand[]
}) {
  const [parts, setParts] = useState<ComposerPart[]>(initialParts)
  useEffect(() => {
    if (initialParts !== EMPTY_PARTS) {
      setParts(initialParts)
    }
  }, [initialParts])
  return (
    <div>
      <ThreadComposer
        parts={parts}
        pending={false}
        disabled={disabled}
        onPartsChange={setParts}
        onSubmit={onSubmit}
        onCommand={onCommand}
        storageService={service}
        hashFile={hashFile}
        historicalUserMessages={historicalUserMessages}
        focusOnEscape={focusOnEscape}
        settings={settings}
        commands={commands}
      />
      <pre data-testid="parts">{JSON.stringify(parts)}</pre>
    </div>
  )
}

function editorOf(): HTMLElement {
  return screen.getByLabelText('给 AI 发送消息')
}

function placeCaretAtEnd(editor: HTMLElement) {
  editor.focus()
  const range = document.createRange()
  range.selectNodeContents(editor)
  range.collapse(false)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

describe('ThreadComposer edge interactions', () => {
  beforeEach(() => {
    vi.mocked(hashFile).mockClear()
    vi.mocked(MODEL_SETTINGS.onModelChange).mockClear()
    vi.mocked(MODEL_SETTINGS.onYoloChange).mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('does nothing for disabled submit and file paste', async () => {
    const onSubmit = vi.fn()
    const { service } = fakeStorage()
    render(
      <Harness
        service={service}
        initialParts={[createTextPart('hello')]}
        onSubmit={onSubmit}
        disabled
      />,
    )
    const editor = editorOf()
    expect(editor).toHaveAttribute('aria-disabled', 'true')

    await userEvent.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).not.toHaveBeenCalled()

    fireEvent.paste(editor, {
      clipboardData: { files: [fileOf('blocked.png', 'image/png')], getData: () => '' },
    })
    // disabled 时文件粘贴是 no-op：不创建 pill，也不进入上传注册表。
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
    expect(document.querySelector('.thread-composer-strip')).toBeNull()
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'hello' }])
    // 附加上传管线完全未被触及。
    expect(partsSnapshot()).toHaveLength(1)
  })

  it('toggles the plus palette open and closed', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const add = screen.getByRole('button', { name: '打开命令表' })
    expect(add).toHaveAttribute('aria-expanded', 'false')

    await user.click(add)
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(add).toHaveAttribute('aria-expanded', 'true')

    // 再次点击 plus 关闭 palette（复用 closeCommandPalette 路径）。
    await user.click(add)
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(add).toHaveAttribute('aria-expanded', 'false')
  })

  it('walks empty and all-disabled command lists without activating', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    const allDisabled = THREAD_COMMANDS.map((command) => ({ ...command, disabled: true }))
    const { rerender } = render(
      <Harness onCommand={onCommand} commands={allDisabled} />,
    )
    const editor = editorOf()

    // 全部命令禁用：Enter 命中 disabled 命令后 fallback 也为空，palette 保持打开。
    await user.type(editor, '/thread')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).not.toHaveBeenCalled()
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()

    // ArrowUp/Down 在「无可用命令」时把 activeIndex 收敛到 0，不越界。
    await user.keyboard('{ArrowDown}{ArrowUp}')
    expect(onCommand).not.toHaveBeenCalled()

    // 查询无匹配：filteredCommands 为空，Enter 无 fallback，palette 不关闭。
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    await user.type(editor, '/zzz-no-match')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).not.toHaveBeenCalled()
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()

    // 无匹配 palette 的关闭路径走 editor Escape。
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    rerender(<Harness onCommand={onCommand} commands={THREAD_COMMANDS} />)
  })

  it.each([
    { readOnly: false, expected: 'shell' },
    { readOnly: true, expected: 'shortcuts' },
  ])('wraps ArrowUp to $expected in the plus palette when readOnly=$readOnly', async ({ readOnly, expected }) => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    // 普通草稿环绕到 shell；只读草稿必须跳过末尾禁用项，落到 shortcuts。
    const commands = threadCommandsForTarget({ kind: 'NEW_SESSION_DRAFT' }, { readOnly })
    render(<Harness onCommand={onCommand} commands={commands} />)
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    await user.keyboard('{ArrowUp}')
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledTimes(1)
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: expected }))
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })

  it('closes the settings menu from an editor mousedown and from Escape', async () => {
    const user = userEvent.setup()
    render(<Harness settings={MODEL_SETTINGS} />)
    const editor = editorOf()

    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()

    // editor mousedown 关闭底栏菜单（同时命中 document 外部点击兜底）。
    fireEvent.mouseDown(editor)
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()

    // 重新打开后按 Escape 关闭（listbox 拥有焦点，菜单自身处理 Escape）。
    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()
    expect(editor).toHaveFocus()
  })

  it('auto-closes the open overlay when typing into the editor', async () => {
    const user = userEvent.setup()
    render(<Harness settings={MODEL_SETTINGS} />)
    const editor = editorOf()

    // plus palette 打开时输入文本：syncFromDom 关闭 palette 并保留草稿。
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('draft')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'draft' }])

    // settings menu 打开时输入文本：syncFromDom 关闭 menu。
    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()
    placeCaretAtEnd(editor)
    await user.keyboard(' more')
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'draft more' }])

    // 输入内容与当前 parts 完全一致时，syncFromDom 的 else 路径不回流草稿。
    const range = document.createRange()
    range.selectNodeContents(editor)
    range.collapse(false)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(range)
    fireEvent.input(editor, { inputType: 'insertText', data: '' })
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'draft more' }])
  })

  it('starts slash mode while a settings menu is open and closes the menu', async () => {
    const user = userEvent.setup()
    render(<Harness settings={MODEL_SETTINGS} />)
    const editor = editorOf()

    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()

    // 直接聚焦 editor 输入 '/'（不经 mousedown），startedSlash 命中 controlMenu
    // 清理路径，同时 syncFromDom 也关闭 menu。
    editor.focus()
    await user.keyboard('/')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()
  })

  it('closes overlays and restores focus on global Escape', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<Harness focusOnEscape onCommand={onCommand} settings={MODEL_SETTINGS} />)
    const editor = editorOf()

    // control menu 打开时全局 Escape：closeOverlay 优先关闭 menu。
    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()
    editor.focus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()
    expect(editor).toHaveFocus()

    // slash palette 关闭后保留草稿，不能靠删除文本来隐藏提示。
    await user.type(editor, '/stop')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(partsSnapshot()).toEqual([{ type: 'text', text: '/stop' }])
  })

  it('inserts dropped plain text at the caret without creating pills', async () => {
    render(<Harness />)
    const editor = editorOf()
    editor.focus()
    const textNode = document.createTextNode('pre')
    editor.appendChild(textNode)
    const range = document.createRange()
    range.setStartAfter(textNode)
    range.collapse(true)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(range)
    fireEvent.input(editor, { inputType: 'insertText', data: 'pre' })
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'pre' }])

    fireEvent.drop(editor, {
      dataTransfer: {
        files: [],
        getData: (type: string) => (type === 'text/plain' ? ' dropped ' : ''),
      },
    })
    await waitFor(() =>
      expect(partsSnapshot()).toEqual([{ type: 'text', text: 'pre dropped ' }]),
    )
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)

    // dataTransfer 缺失时 files 兜底为空数组，同样不产生 pill。
    fireEvent.drop(editor, {})
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'pre dropped ' }])
  })

  it('ignores paste without clipboard data', async () => {
    render(<Harness />)
    const editor = editorOf()
    editor.focus()
    editor.appendChild(document.createTextNode('keep'))
    fireEvent.input(editor, { inputType: 'insertText', data: 'keep' })

    fireEvent.paste(editor, { clipboardData: null })
    // 无剪贴板数据：不 preventDefault、不创建任何 pill。
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'keep' }])
  })

  it('keeps an attachment draft untouched when navigating history', async () => {
    render(
      <Harness
        initialParts={[
          createTextPart('keep attachment'),
          createAttachmentPart('local-1', 'a.txt'),
        ]}
        historicalUserMessages={['history']}
      />,
    )
    const editor = editorOf()
    placeCaretAtEnd(editor)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })

    // 草稿含 attachment 时历史浏览被拒绝：内容与 pill 保持原样。
    expect(editor).toHaveTextContent('keep attachment')
    expect(partsSnapshot()).toEqual([
      { type: 'text', text: 'keep attachment' },
      { type: 'attachment', uploadId: 'local-1', filename: 'a.txt' },
    ])
  })

  it('does not recall history when the caret is missing or spans outside the editor', () => {
    const props = {
      initialParts: [createTextPart('current draft')],
      historicalUserMessages: ['history 1'],
    }
    const { rerender } = render(<Harness {...props} />)
    const editor = editorOf()

    // 无 selection（rangeCount 0）：ArrowUp 不导航历史。
    window.getSelection()?.removeAllRanges()
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('current draft')

    // 非 collapsed selection：跨选区箭头移动交给浏览器，不召回历史。
    placeCaretAtEnd(editor)
    const range = document.createRange()
    range.selectNodeContents(editor)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(range)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('current draft')

    // selection 完全在 editor 之外：同样不导航。
    rerender(<Harness {...props} />)
    const outside = document.createElement('div')
    outside.textContent = 'outside'
    document.body.appendChild(outside)
    const outsideRange = document.createRange()
    outsideRange.selectNodeContents(outside)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(outsideRange)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('current draft')
    outside.remove()
  })

  it('removes a stale DOM pill through the fallback sync path', async () => {
    render(<Harness initialParts={[createTextPart('a')]} />)
    const editor = editorOf()
    placeCaretAtEnd(editor)

    // 手动注入 parts 状态中不存在的过期 pill（模拟外部 DOM 残留）。
    const stale = document.createElement('span')
    stale.className = 'composer-pill'
    stale.contentEditable = 'false'
    stale.dataset.partId = 'stale-1'
    stale.dataset.partType = 'attachment'
    stale.dataset.uploadId = 'ghost'
    stale.dataset.filename = 'ghost.txt'
    stale.textContent = '[ghost.txt]'
    editor.appendChild(stale)

    // caret 位于 stale pill 之后，Backspace：removePartsByIds 无此 partId
    // （key 相同走 else），随后 syncFromDom 从 DOM 提取并回流真实 parts。
    const caretRange = document.createRange()
    caretRange.setStartAfter(stale)
    caretRange.collapse(true)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(caretRange)
    fireEvent.keyDown(editor, { key: 'Backspace' })
    await waitFor(() =>
      expect(partsSnapshot()).toEqual([{ type: 'text', text: 'a' }]),
    )
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(0)
  })

  it('leaves plain-text Backspace to the browser without pill handling', () => {
    render(<Harness initialParts={[createTextPart('ab')]} />)
    const editor = editorOf()
    placeCaretAtEnd(editor)

    // caret 后无 pill：不 preventDefault、不回流，由浏览器默认删除
    //（jsdom 无默认行为，草稿保持不变）。
    fireEvent.keyDown(editor, { key: 'Backspace' })
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'ab' }])
    expect(editor.textContent).toBe('ab')
  })

  it('keeps the file input change with no files as a no-op', async () => {
    const { service } = fakeStorage()
    render(<Harness service={service} />)
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')
    expect(input).not.toBeNull()

    // files 属性缺失（null 兜底）且长度为 0：不创建上传，只清空 input 值。
    fireEvent.change(input!, { target: {} })
    expect(partsSnapshot()).toEqual([])
    expect(document.querySelector('.thread-composer-strip')).toBeNull()
  })

  it('releases an unreferenced upload from the strip without touching parts', async () => {
    const onRemove = vi.fn()
    const upload: AttachmentUpload = {
      localId: 'local-1',
      uploadId: 'up-1',
      filename: 'orphan.txt',
      mediaType: 'text/plain',
      sizeBytes: 128,
      sha256: 'a'.repeat(64),
      status: 'ready',
      progress: 1,
      previewUrl: null,
      detached: false,
    }
    render(
      <div>
        <AttachmentStrip
          uploads={[upload]}
          parts={[createTextPart('keep')]}
          disabled={false}
          onRemove={onRemove}
        />
        <pre data-testid="parts">{JSON.stringify([{ type: 'text', text: 'keep' }])}</pre>
      </div>,
    )
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: /移除附件/ }))
    expect(onRemove).toHaveBeenCalledWith(upload)
    // 该用例断言 strip 对「无引用」条目的移除动作；ThreadComposer 侧对应
    // handleRemoveUpload 的 else 分支（parts 无变化）由下方用例覆盖。
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'keep' }])
  })

  it('submits from the send button while the plus palette is open', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<Harness initialParts={[createTextPart('send it')]} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalledWith(
      [expect.objectContaining({ type: 'text', text: 'send it' })],
      [expect.objectContaining({ type: 'text', text: 'send it' })],
    )
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })

  it('resolves server upload handles on submit and keeps unknown local ids', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const { service } = fakeStorage()
    render(
      <Harness
        service={service}
        initialParts={[createTextPart('with file')]}
        onSubmit={onSubmit}
      />,
    )
    const editor = editorOf()
    fireEvent.paste(editor, {
      clipboardData: { files: [fileOf('b.txt', 'text/plain')], getData: () => '' },
    })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    await waitForIdleUploads()

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalled()
    const payload = onSubmit.mock.calls[0]?.[0] as ComposerPart[]
    expect(payload[0]).toEqual(expect.objectContaining({ type: 'text', text: 'with file' }))
    // 本地 localId 在提交 payload 中解析为服务端 upload 句柄。
    expect(payload[1]).toEqual(
      expect.objectContaining({ type: 'attachment', uploadId: 'up-1', filename: 'b.txt' }),
    )
  })

  it('does not fire a command when /models has no settings', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    const onPartsChange = vi.fn()
    render(
      <ThreadComposer
        parts={[createTextPart('/models')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={vi.fn()}
        onCommand={onCommand}
      />,
    )
    const editor = editorOf()
    await user.click(editor)
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')

    // 无 settings 时 /models 只清空 slash 草稿，不触发 onCommand。
    expect(onCommand).not.toHaveBeenCalled()
    expect(onPartsChange).toHaveBeenCalledWith([])
  })
})

type StorageUploadDTO = Awaited<ReturnType<StorageService['reserveUpload']>>

function PillHarness({
  service = fakeStorage().service,
  initialParts = EMPTY_PARTS,
  onSubmit = vi.fn(),
  hash = hashFile,
}: {
  service?: StorageService
  initialParts?: ComposerPart[]
  onSubmit?: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  hash?: HashFile
}) {
  const [parts, setParts] = useState<ComposerPart[]>(initialParts)
  // 外部整体替换（失败恢复/重放）会推进本地状态；仅当传入新数组时同步。
  useEffect(() => {
    if (initialParts !== EMPTY_PARTS) {
      setParts(initialParts)
    }
  }, [initialParts])
  return (
    <div>
      <ThreadComposer
        parts={parts}
        pending={false}
        disabled={false}
        onPartsChange={setParts}
        onSubmit={onSubmit}
        onCommand={vi.fn()}
        storageService={service}
        hashFile={hash}
      />
      <pre data-testid="parts">{JSON.stringify(parts)}</pre>
    </div>
  )
}

async function pasteFiles(editor: HTMLElement, ...files: File[]) {
  fireEvent.paste(editor, { clipboardData: { files, getData: () => '' } })
  await waitFor(() => expect(document.querySelectorAll('.composer-pill').length).toBe(files.length))
}

function placeCaretInEditor(editor: HTMLElement, beforeChildIndex: number) {
  const range = document.createRange()
  range.setStart(editor, beforeChildIndex)
  range.collapse(true)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

/** 把光标放到首个文本节点的字符偏移处（用于「插入在光标处」测试）。 */
function placeCaretInText(editor: HTMLElement, charOffset: number) {
  const node = editor.firstChild
  if (node == null || node.nodeType !== Node.TEXT_NODE) {
    throw new Error('expected a leading text node')
  }
  const range = document.createRange()
  range.setStart(node, charOffset)
  range.collapse(true)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

function placeCaretAtEndOf(editor: HTMLElement) {
  const range = document.createRange()
  range.selectNodeContents(editor)
  range.collapse(false)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

/**
 * jsdom + user-event 不会在 contenteditable 中推进 document selection（每次
 * 键入都插到原 caret），因此测试用 Selection API 逐字符插入并手动维护 caret，
 * 与真实浏览器行为一致。
 */
async function typeInEditor(editor: HTMLElement, text: string) {
  editor.focus()
  for (const char of text) {
    const selection = window.getSelection()
    let range = selection?.rangeCount ? selection.getRangeAt(0) : null
    if (!range || !editor.contains(range.commonAncestorContainer)) {
      placeCaretAtEndOf(editor)
      range = selection?.getRangeAt(0) ?? null
    }
    if (!range) {
      continue
    }
    const node = document.createTextNode(char)
    range.deleteContents()
    range.insertNode(node)
    range.setStartAfter(node)
    range.collapse(true)
    selection?.removeAllRanges()
    if (selection) {
      selection.addRange(range)
    }
    fireEvent.input(editor, { inputType: 'insertText', data: char })
  }
}

describe('ThreadComposer attachment pills', () => {
  beforeEach(() => {
    vi.mocked(hashFile).mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('serializes ordered text -> pill -> text parts from typing and paste', async () => {
    const { service } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, 'hello')
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'hello' }])

    await pasteFiles(editor, fileOf('photo.png', 'image/png'))
    expect(partsSnapshot()).toEqual([
      { type: 'text', text: 'hello' },
      expect.objectContaining({ type: 'attachment', filename: 'photo.png' }) as Record<string, string>,
    ])
    const pill = document.querySelector('.composer-pill')
    expect(pill).not.toBeNull()
    expect(pill?.getAttribute('data-part-id')).toBeTruthy()
    expect(pill?.getAttribute('data-upload-id')).toBeTruthy()
    expect((pill as HTMLElement).contentEditable).toBe('false')
    expect(pill).toHaveTextContent('[photo.png]')
    expect(document.querySelector('.attachment-reference')).not.toBeNull()

    // 继续在 pill 之后输入文本：ordered parts 保持 text -> attachment -> text。
    await typeInEditor(editor, 'after')
    expect(partsSnapshot()).toEqual([
      { type: 'text', text: 'hello' },
      expect.objectContaining({ type: 'attachment', filename: 'photo.png' }) as Record<string, string>,
      { type: 'text', text: 'after' },
    ])
    // typed '@filename' 保持纯文本，绝不创建 pill。
    await typeInEditor(editor, '@report.pdf')
    expect(partsSnapshot()).toEqual([
      { type: 'text', text: 'hello' },
      expect.objectContaining({ type: 'attachment', filename: 'photo.png' }) as Record<string, string>,
      { type: 'text', text: 'after@report.pdf' },
    ])
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(1)
  })

  it('renders local image/video thumbnails and type-specific icons without upload syntax', async () => {
    const user = userEvent.setup()
    const createObjectUrl = vi
      .spyOn(URL, 'createObjectURL')
      .mockImplementation((file) => `blob:${(file as File).name}`)
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL')
    const { service } = fakeStorage()
    const { unmount } = render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    await pasteFiles(
      editor,
      fileOf('scene.png', 'image/png'),
      fileOf('clip.mp4', 'video/mp4'),
      fileOf('voice.mp3', 'audio/mpeg'),
      fileOf('bundle.zip', 'application/zip'),
      fileOf('table.csv', 'text/csv'),
      fileOf('script.ts', 'text/typescript'),
      fileOf('brief.pdf', 'application/pdf'),
      fileOf('payload.bin', 'application/octet-stream'),
    )

    const imageItem = screen.getByTitle('scene.png').closest('.attachment-reference')
    const videoItem = screen.getByTitle('clip.mp4').closest('.attachment-reference')
    const audioItem = screen.getByTitle('voice.mp3').closest('.attachment-reference')
    const archiveItem = screen.getByTitle('bundle.zip').closest('.attachment-reference')
    const spreadsheetItem = screen.getByTitle('table.csv').closest('.attachment-reference')
    const codeItem = screen.getByTitle('script.ts').closest('.attachment-reference')
    const pdfItem = screen.getByTitle('brief.pdf').closest('.attachment-reference')
    const genericItem = screen.getByTitle('payload.bin').closest('.attachment-reference')
    expect(imageItem?.querySelector('img')).toHaveAttribute('src', 'blob:scene.png')
    expect(videoItem?.querySelector('video')).toHaveAttribute('src', 'blob:clip.mp4')
    expect(videoItem?.querySelector('video')).toHaveAttribute('preload', 'auto')
    expect(audioItem?.querySelector('[data-file-icon="audio"]')).not.toBeNull()
    expect(archiveItem?.querySelector('[data-file-icon="archive"]')).not.toBeNull()
    expect(spreadsheetItem?.querySelector('[data-file-icon="spreadsheet"]')).not.toBeNull()
    expect(codeItem?.querySelector('[data-file-icon="code"]')).not.toBeNull()
    expect(pdfItem?.querySelector('[data-file-icon="text"]')).not.toBeNull()
    expect(genericItem?.querySelector('[data-file-icon="file"]')).not.toBeNull()
    expect(screen.getAllByText('[scene.png]')).toHaveLength(2)
    expect(screen.getAllByText('[clip.mp4]')).toHaveLength(2)
    const editorPills = [...document.querySelectorAll('.composer-pill')]
    expect(editorPills.map((pill) => pill.textContent)).toEqual([
      '[scene.png]',
      '[clip.mp4]',
      '[voice.mp3]',
      '[bundle.zip]',
      '[table.csv]',
      '[script.ts]',
      '[brief.pdf]',
      '[payload.bin]',
    ])
    expect(imageItem?.querySelector('.attachment-reference-name')).toHaveTextContent('[scene.png]')
    expect(document.querySelector('.thread-composer-strip')).not.toHaveTextContent('(upload)')
    expect(document.querySelector('.composer-editor')).not.toHaveTextContent('(upload)')
    expect(createObjectUrl).toHaveBeenCalledTimes(2)

    await user.click(screen.getByRole('button', { name: '预览 scene.png' }))
    const imageDialog = screen.getByRole('dialog', { name: '预览 scene.png' })
    expect(imageDialog.querySelector('img')).toHaveAttribute('src', 'blob:scene.png')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '预览 clip.mp4' }))
    const videoDialog = screen.getByRole('dialog', { name: '预览 clip.mp4' })
    expect(videoDialog.querySelector('video')).toHaveAttribute('src', 'blob:clip.mp4')
    await user.click(screen.getByRole('button', { name: '关闭媒体预览' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    unmount()
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:scene.png')
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:clip.mp4')
  })

  it('inserts pasted pills at the caret between text', async () => {
    const { service } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, 'ab')
    placeCaretInText(editor, 1)
    fireEvent.paste(editor, { clipboardData: { files: [fileOf('mid.png', 'image/png')], getData: () => '' } })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    // 插入发生在光标处：text -> pill -> text，而不是追加到末尾。
    expect(partsSnapshot()).toEqual([
      { type: 'text', text: 'a' },
      expect.objectContaining({ type: 'attachment', filename: 'mid.png' }) as Record<string, string>,
      { type: 'text', text: 'b' },
    ])
  })

  it('keeps duplicate filenames unambiguous by uploadId with derived display suffix', async () => {
    const onSubmit = vi.fn()
    const { service } = fakeStorage()
    render(<PillHarness service={service} onSubmit={onSubmit} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await pasteFiles(editor, fileOf('report.pdf', 'application/pdf'), fileOf('report.pdf', 'application/pdf', 256))
    expect(partsSnapshot()).toEqual([
      expect.objectContaining({ type: 'attachment', filename: 'report.pdf' }) as Record<string, string>,
      expect.objectContaining({ type: 'attachment', filename: 'report.pdf' }) as Record<string, string>,
    ])
    // 草稿中的客户端 id 互不相同（同名但内容不同 => 两个 upload）。
    const uploadIds = partsSnapshot().map((part) => part.uploadId)
    expect(new Set(uploadIds).size).toBe(2)
    // 同名文件的展示后缀按顺序派生。
    expect(screen.getByText('[report.pdf (1)]')).toBeInTheDocument()
    expect(screen.getByText('[report.pdf (2)]')).toBeInTheDocument()
    // 提交 payload 中解析为互不相同的服务端 upload 句柄。
    await waitForIdleUploads()
    placeCaretAtEndOf(editor)
    fireEvent.keyDown(editor, { key: 'Enter' })
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const sent = onSubmit.mock.calls[0]?.[0] as ComposerPart[]
    const sentIds = sent.map((part) => (part.type === 'attachment' ? part.uploadId : ''))
    expect(sentIds[0]).toMatch(/^up-\d+$/)
    expect(sentIds[0]).not.toBe(sentIds[1])
  })

  it('gives identical-metadata files distinct handles without client dedup', async () => {
    const { service, reserveUpload } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const file = fileOf('clip.mp4', 'video/mp4')
    // 同一文件粘贴两次：每个文件都是独立的 upload（不做文件名+大小+MIME 去重）。
    await pasteFiles(editor, file, file)
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(2)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    const uploadIds = partsSnapshot().map((part) => part.uploadId)
    expect(new Set(uploadIds).size).toBe(2)
    await waitForIdleUploads()
    expect(reserveUpload).toHaveBeenCalledTimes(2)
    // 两颗 pill 各自的句柄独立释放。
    placeCaretInEditor(editor, 1)
    fireEvent.keyDown(editor, { key: 'Backspace' })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('deletes a whole pill with Backspace and Delete', async () => {
    const { service } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, 'a')
    await pasteFiles(editor, fileOf('data.txt', 'text/plain'))
    // caret 位于 pill 之后：Backspace 移除整颗 pill。
    placeCaretInEditor(editor, 2)
    fireEvent.keyDown(editor, { key: 'Backspace' })
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'a' }])
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)

    // Delete：caret 位于 pill 之前时整颗删除。
    await pasteFiles(editor, fileOf('data.txt', 'text/plain'))
    placeCaretInEditor(editor, 1)
    fireEvent.keyDown(editor, { key: 'Delete' })
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'a' }])
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
  })

  it('uploads via PENDING (direct PUT) and gates send on ready', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    let releaseHash: () => void = () => undefined
    const hashGate = new Promise<void>((resolve) => {
      releaseHash = resolve
    })
    const gatedHash: HashFile = async (_file) => {
      await hashGate
      return 'a'.repeat(64)
    }
    const { service, reserveUpload, uploadFile, completeUpload } = fakeStorage()
    render(<PillHarness service={service} onSubmit={onSubmit} hash={gatedHash} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, 'look')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
    await pasteFiles(editor, fileOf('photo.png', 'image/png', 64))
    // 上传中（hash 阶段）阻塞发送。
    await waitFor(() => expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled())
    expect(screen.getByText(/上传中… 10%/)).toBeInTheDocument()
    releaseHash()
    await waitForIdleUploads()
    // wire 请求只有 filename/mediaType/sizeBytes/sha256（mediaKind 是本地分类键）。
    expect(reserveUpload).toHaveBeenCalledWith(expect.objectContaining({
      filename: 'photo.png',
      mediaType: 'image/png',
      sizeBytes: 64,
      sha256: 'a'.repeat(64),
    }))
    expect(reserveUpload.mock.calls[0]?.[0]).not.toHaveProperty('mediaKind')
    // PENDING = 必须直传后 complete；句柄在 complete 后保持不变。
    expect(uploadFile).toHaveBeenCalledTimes(1)
    expect(completeUpload).toHaveBeenCalledWith('up-1')
    await waitFor(() => expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalled()
    // 提交 payload 中 uploadId 是服务端句柄（不是 blobId）。
    const sent = onSubmit.mock.calls[0]?.[0] as ComposerPart[]
    expect(sent[0]).toEqual(expect.objectContaining({ type: 'text', text: 'look' }))
    expect(sent[1]).toEqual(expect.objectContaining({ type: 'attachment', uploadId: 'up-1' }))
  })

  it('skips direct PUT for READY (sha256 hit) reservations', async () => {
    const { service, reserveUpload, uploadFile, completeUpload } = fakeStorage({
      reserve: (request) => ({
        id: `dedup-${request.sha256.slice(0, 8)}`,
        state: 'READY',
        blobId: 'blob-existing',
        presignedPut: null,
        expiresAt: 0,
      }),
    })
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await pasteFiles(editor, fileOf('photo.png', 'image/png'))
    await waitForIdleUploads()
    expect(reserveUpload).toHaveBeenCalledTimes(1)
    // READY = sha256 命中已有内容：跳过直传，直接 complete。
    expect(uploadFile).not.toHaveBeenCalled()
    expect(completeUpload).toHaveBeenCalledWith('dedup-aaaaaaaa')
  })

  it('shows localized upload failure toast, rolls back failed pills, and enables text submission without retry UI', async () => {
    // 测试意图：预留失败必须原子撤销附件、保留文本并解除发送门禁，不留下重试态。
    const user = userEvent.setup()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:broken.png')
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL')
    const { service } = fakeStorage({ failReserveTimes: 1 })
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, '保留文本')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()

    await pasteFiles(editor, fileOf('broken.png', 'image/png'))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('broken.png 上传失败：reserve unavailable'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /重试/ })).not.toBeInTheDocument()
    expect(partsSnapshot()).toEqual([{ type: 'text', text: '保留文本' }])
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:broken.png')

    await user.click(screen.getByRole('button', { name: '关闭通知' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('cleans up reserved upload and rolls back pills when PUT fails', async () => {
    // 测试意图：直传失败后必须释放已预留句柄并同步清除 strip、DOM 与草稿引用。
    const { service, deleteUpload } = fakeStorage({ failUploadTimes: 1 })
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await pasteFiles(editor, fileOf('put-fail.bin', 'application/octet-stream'))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('put-fail.bin 上传失败：upload failed'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    await waitFor(() => expect(deleteUpload).toHaveBeenCalledWith('up-1'))
  })

  it('cleans up reserved upload and rolls back pills when complete fails', async () => {
    // 测试意图：完成确认失败与直传失败采用相同的句柄清理和草稿回滚语义。
    const { service, deleteUpload } = fakeStorage({ failCompleteTimes: 1 })
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await pasteFiles(editor, fileOf('complete-fail.bin', 'application/octet-stream'))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('complete-fail.bin 上传失败：complete failed'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    await waitFor(() => expect(deleteUpload).toHaveBeenCalledWith('up-1'))
  })

  it('creates pills from drop and the hidden file picker', async () => {
    const { service } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    fireEvent.drop(editor, { dataTransfer: { files: [fileOf('drop.png', 'image/png')], getData: () => '' } })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    expect(partsSnapshot()[0]).toEqual(expect.objectContaining({ type: 'attachment', filename: 'drop.png' }) as Record<string, string>)

    const input = document.querySelector<HTMLInputElement>('input[type="file"]')
    expect(input).not.toBeNull()
    fireEvent.change(input!, { target: { files: [fileOf('picked.txt', 'text/plain')] } })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(2))
    expect(partsSnapshot()[1]).toEqual(expect.objectContaining({ type: 'attachment', filename: 'picked.txt' }) as Record<string, string>)
  })

  it('uploads clipboard item files while preserving ordinary text paste', async () => {
    const { service } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    editor.focus()
    const pasted = fileOf('clipboard.png', 'image/png')

    const filePasteAllowed = fireEvent.paste(editor, {
      clipboardData: {
        files: [],
        items: [{ kind: 'file', getAsFile: () => pasted }],
        getData: () => '',
      },
    })
    expect(filePasteAllowed).toBe(false)
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    expect(partsSnapshot()[0]).toEqual(
      expect.objectContaining({ type: 'attachment', filename: 'clipboard.png' }) as Record<string, string>,
    )

    placeCaretAtEndOf(editor)
    const textPasteAllowed = fireEvent.paste(editor, {
      clipboardData: {
        files: [],
        items: [{ kind: 'string', getAsFile: () => null }],
        getData: (type: string) => type === 'text/plain' ? 'plain text' : '',
      },
    })
    expect(textPasteAllowed).toBe(false)
    expect(partsSnapshot()).toEqual([
      expect.objectContaining({ type: 'attachment', filename: 'clipboard.png' }) as Record<string, string>,
      { type: 'text', text: 'plain text' },
    ])
  })

  it('rejects oversized video, audio and generic files with localized toast notifications', async () => {
    // 测试意图：各媒体大小门禁都只展示错误通知，校验失败文件不得进入草稿或注册表。
    const { service } = fakeStorage()
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    const bigVideo = new File([new Uint8Array(100 * 1024 * 1024 + 1)], 'huge.mp4', { type: 'video/mp4' })
    fireEvent.paste(editor, { clipboardData: { files: [bigVideo], getData: () => '' } })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('huge.mp4 上传失败：huge.mp4 超过 100 MB 上传限制'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)

    const bigAudio = new File([new Uint8Array(15 * 1024 * 1024 + 1)], 'huge.mp3', { type: 'audio/mpeg' })
    fireEvent.paste(editor, { clipboardData: { files: [bigAudio], getData: () => '' } })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('huge.mp3 上传失败：huge.mp3 超过 15 MB 上传限制'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)

    const bigFile = new File([new Uint8Array(30 * 1024 * 1024 + 1)], 'huge.bin', { type: 'application/octet-stream' })
    fireEvent.paste(editor, { clipboardData: { files: [bigFile], getData: () => '' } })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('huge.bin 上传失败：huge.bin 超过 30 MB 上传限制'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
  })

  it('preserves active IME composition through upload failure and defers pill rollback until compositionend', async () => {
    // 测试意图：异步失败不能重建组合态 DOM；附件回滚延迟到汉字完成上屏后执行。
    let rejectReserve!: (err: Error) => void
    const reservePromise = new Promise<StorageUploadDTO>((_, reject) => {
      rejectReserve = reject
    })
    const { service } = fakeStorage({
      reserve: () => reservePromise,
    })
    render(<PillHarness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    // 1. 发起上传，此时 pill 节点在 DOM 中且后台开始 reserve
    await pasteFiles(editor, fileOf('failing.bin', 'application/octet-stream'))
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(1)

    // 2. 上传期间用户开始中文拼音输入，DOM 中插入未确认拼音节点
    fireEvent.compositionStart(editor)
    const imeSpan = document.createElement('span')
    imeSpan.className = 'ime-unconfirmed'
    imeSpan.textContent = 'ceshi'
    editor.appendChild(imeSpan)
    fireEvent.input(editor, { isComposing: true })

    // 3. 后台上传失败，弹出错误提示，但因 IME 正在组合，DOM pill 回滚被推迟
    rejectReserve(new Error('reserve unavailable'))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('failing.bin 上传失败：reserve unavailable'))

    // 4. 验证组合态期间未确认的 IME DOM 节点完全保留，未被全量重建抹除，pill 此时亦保持原地未变
    expect(editor.querySelector('.ime-unconfirmed')).toBeInTheDocument()
    expect(editor.querySelector('.ime-unconfirmed')?.textContent).toBe('ceshi')
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(1)

    // 5. 组合态结束（上屏最终汉字并结束组合）
    imeSpan.remove()
    editor.appendChild(document.createTextNode('测试'))
    fireEvent.compositionEnd(editor)

    // 6. 最终汉字完整保留且仅出现一次，失败的 pill 此时已被清理
    expect(partsSnapshot()).toEqual([{ type: 'text', text: '测试' }])
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
  })

  it('normalizes CRLF to LF, prevents default HTML insertion, and preserves caret position', async () => {
    // 测试意图：纯文本粘贴只插入 text/plain，统一换行并把光标留在插入内容之后。
    render(<PillHarness initialParts={[{ type: 'text', partId: 'text-1', text: 'ab' }]} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    editor.focus()
    placeCaretInText(editor, 1)

    const prevented = fireEvent.paste(editor, {
      clipboardData: {
        files: [],
        items: [],
        getData: (type: string) => {
          if (type === 'text/plain') return 'line1\r\nline2\rline3'
          if (type === 'text/html') return '<p>line1</p><p>line2</p>'
          return ''
        },
      },
    })
    expect(prevented).toBe(false)
    expect(partsSnapshot()).toEqual([{ type: 'text', text: 'aline1\nline2\nline3b' }])
    expect(editor.querySelector('p')).toBeNull()

    const selection = window.getSelection()
    expect(selection?.rangeCount).toBe(1)
    const caret = selection!.getRangeAt(0)
    expect(caret.collapsed).toBe(true)
    const beforeCaret = document.createRange()
    beforeCaret.selectNodeContents(editor)
    beforeCaret.setEnd(caret.startContainer, caret.startOffset)
    expect(beforeCaret.toString()).toBe('aline1\nline2\nline3')
  })

  it('automatically dismisses toast after 5 seconds', async () => {
    // 测试意图：错误提示无需用户操作也会按约定超时清理，不永久遮挡会话内容。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const { service } = fakeStorage({ failReserveTimes: 1 })
      render(<PillHarness service={service} />)
      const editor = screen.getByLabelText('给 AI 发送消息')
      fireEvent.paste(editor, {
        clipboardData: {
          files: [fileOf('fail.bin', 'application/octet-stream')],
          getData: () => '',
        },
      })

      await vi.waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
      expect(screen.getByRole('alert')).toHaveTextContent('fail.bin 上传失败')

      vi.advanceTimersByTime(5000)
      await vi.waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    } finally {
      vi.useRealTimers()
    }
  })

  it('rebuilds a replaced same-name attachment with a fresh partId', async () => {
    const { service } = fakeStorage()
    const first = createAttachmentPart('local-1', 'report.pdf')
    const second = createAttachmentPart('local-2', 'report.pdf')
    const { rerender } = render(<PillHarness service={service} initialParts={[first]} />)
    const firstPill = document.querySelector('.composer-pill')
    const firstPartId = firstPill?.getAttribute('data-part-id')
    expect(firstPill?.getAttribute('data-upload-id')).toBe('local-1')

    // 同名附件 A -> B（不同 localId）：DOM 必须重建，不得保留过期 partId。
    rerender(<PillHarness service={service} initialParts={[second]} />)
    await waitFor(() => expect(document.querySelector('.composer-pill')?.getAttribute('data-upload-id')).toBe('local-2'))
    const secondPill = document.querySelector('.composer-pill')
    expect(secondPill?.getAttribute('data-part-id')).not.toBe(firstPartId)
  })

  it('rebuilds the correct partId when one occurrence of the same handle is replaced', async () => {
    const { service } = fakeStorage()
    // 同一 upload 句柄的两个 occurrence（显式重复的 pill action）。
    const first = createAttachmentPart('up-x', 'report.pdf')
    const second = createAttachmentPart('up-x', 'report.pdf')
    const replacement = createAttachmentPart('up-x', 'report.pdf')
    const { rerender } = render(<PillHarness service={service} initialParts={[first, second]} />)
    const pills = document.querySelectorAll('.composer-pill')
    expect(pills).toHaveLength(2)
    const firstPartId = pills[0]?.getAttribute('data-part-id')
    const secondPartId = pills[1]?.getAttribute('data-part-id')
    expect(firstPartId).not.toBe(secondPartId)
    expect(pills[0]?.getAttribute('data-upload-id')).toBe('up-x')

    // 替换第一个 occurrence（同句柄、新 partId）：DOM 必须重建出新的 partId。
    rerender(<PillHarness service={service} initialParts={[replacement, second]} />)
    await waitFor(() => {
      const rebuilt = document.querySelectorAll('.composer-pill')
      expect(rebuilt).toHaveLength(2)
      expect(rebuilt[0]?.getAttribute('data-part-id')).toBe(replacement.partId)
      expect(rebuilt[0]?.getAttribute('data-part-id')).not.toBe(firstPartId)
      expect(rebuilt[1]?.getAttribute('data-part-id')).toBe(secondPartId)
    })
  })

  it('deletes a single occurrence of a repeated same-handle pill by partId', async () => {
    const { service } = fakeStorage()
    const first = createAttachmentPart('up-x', 'report.pdf')
    const second = createAttachmentPart('up-x', 'report.pdf')
    render(<PillHarness service={service} initialParts={[first, second]} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(2)
    // caret 位于第一颗 pill 之后：Backspace 只移除该 occurrence。
    placeCaretInEditor(editor, 1)
    fireEvent.keyDown(editor, { key: 'Backspace' })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    const remaining = partsSnapshot()
    expect(remaining).toHaveLength(1)
    expect(remaining[0]).toEqual(
      expect.objectContaining({ type: 'attachment', uploadId: 'up-x', filename: 'report.pdf' }) as Record<string, string>,
    )
  })

  it('keeps slash palette behavior and IME-safe Enter', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const { service } = fakeStorage()
    render(<PillHarness service={service} onSubmit={onSubmit} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, '/')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    // IME 组合中的 Enter 不提交也不关闭 palette。
    fireEvent.keyDown(editor, { key: 'Enter', isComposing: true })
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()
    // 非组合 Enter 在 palette 打开时命中命令而非发送。
    await user.keyboard('{Enter}')
    expect(onSubmit).not.toHaveBeenCalled()
    // 普通文本 Enter 提交。
    await typeInEditor(editor, 'hello')
    await user.keyboard('{Enter}')
    expect(onSubmit).toHaveBeenCalled()
  })

  it('restores the local-id draft after a failed send with an attachment', async () => {
    const user = userEvent.setup()
    const { service, deleteUpload } = fakeStorage()
    function Controlled() {
      const [parts, setParts] = useState<ComposerPart[]>([])
      return (
        <div>
          <ThreadComposer
            parts={parts}
            pending={false}
            disabled={false}
            onPartsChange={setParts}
            onSubmit={(_payload, localDraft) => {
              // 模拟 controller：payload 用于构建 batch，localDraft 用于恢复
              //（服务端 uploadId 绝不回流到 draft）。
              setParts([])
              setTimeout(() => {
                setParts(localDraft)
              }, 10)
            }}
            onCommand={vi.fn()}
            storageService={service}
            hashFile={hashFile}
          />
          <pre data-testid="parts">{JSON.stringify(parts)}</pre>
        </div>
      )
    }
    render(<Controlled />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, 'keep')
    await pasteFiles(editor, fileOf('kept.png', 'image/png'))
    const localUploadId = partsSnapshot()[1]?.uploadId
    await waitForIdleUploads()
    await user.keyboard('{Enter}')
    // 恢复后 pill 与紧凑引用都还在，且没有触发 DELETE。
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1))
    expect(deleteUpload).not.toHaveBeenCalled()
    expect(partsSnapshot()).toEqual([
      { type: 'text', text: 'keep' },
      expect.objectContaining({ type: 'attachment', filename: 'kept.png' }) as Record<string, string>,
    ])
    // 恢复的是本地 id 草稿（不是服务端句柄）。
    expect(partsSnapshot()[1]?.uploadId).toBe(localUploadId)
    expect(document.querySelector('.composer-pill')?.getAttribute('data-upload-id')).toBe(localUploadId)
  })

  it('manages imageTier selection (default 720P, options 1080P/ORIGINAL) and submits it in payload/draft', async () => {
    // 测试意图：验证图片附件默认赋予 720P 档位，用户可修改为 1080P 或 ORIGINAL，修改后同步 DOM pill 与 parts，
    // 提交时 payload 与 localDraft 均保留该档位，且非图片媒体不暴露档位选择并在内容载荷中省略 imageTier。
    const user = userEvent.setup()
    const { service } = fakeStorage()
    let submittedPayload: ComposerPart[] = []
    let submittedDraft: ComposerPart[] = []

    function Controlled() {
      const [parts, setParts] = useState<ComposerPart[]>([])
      return (
        <div>
          <ThreadComposer
            parts={parts}
            pending={false}
            disabled={false}
            onPartsChange={setParts}
            onSubmit={(payload, localDraft) => {
              submittedPayload = payload
              submittedDraft = localDraft
            }}
            onCommand={vi.fn()}
            storageService={service}
            hashFile={hashFile}
          />
          <pre data-testid="parts">{JSON.stringify(parts)}</pre>
        </div>
      )
    }

    render(<Controlled />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await typeInEditor(editor, 'check images')
    await pasteFiles(editor, fileOf('shot.png', 'image/png'), fileOf('doc.pdf', 'application/pdf'))
    await waitForIdleUploads()

    // 只有图片有档位选择下拉框（doc.pdf 没有）
    const tierTriggers = screen.getAllByRole('button', { name: /图片清晰度/ })
    expect(tierTriggers).toHaveLength(1)
    expect(tierTriggers[0]).toHaveAttribute('data-value', '720P')

    // 默认 pill 与 parts 均为 720P
    const imgPill = document.querySelector('.composer-pill[data-filename="shot.png"]')
    expect(imgPill?.getAttribute('data-image-tier')).toBe('720P')

    // 用户切换档位至 1080P
    await user.click(tierTriggers[0]!)
    await user.click(screen.getByRole('option', { name: '1080P' }))
    expect(screen.getByRole('button', { name: /图片清晰度/ })).toHaveAttribute('data-value', '1080P')
    expect(imgPill?.getAttribute('data-image-tier')).toBe('1080P')

    // 提交消息（聚焦 editor 并按回车）
    fireEvent.keyDown(editor, { key: 'Enter' })
    expect(submittedPayload).toHaveLength(3) // text + 2 attachments
    expect(submittedPayload[1]).toMatchObject({
      type: 'attachment',
      filename: 'shot.png',
      imageTier: '1080P',
    })
    expect(submittedDraft[1]).toMatchObject({
      type: 'attachment',
      filename: 'shot.png',
      imageTier: '1080P',
    })

    // 非图片附件 doc.pdf 不携带 imageTier
    expect(submittedPayload[2]).toMatchObject({
      type: 'attachment',
      filename: 'doc.pdf',
    })
    expect((submittedPayload[2] as { imageTier?: unknown }).imageTier).toBeUndefined()
    expect((submittedDraft[2] as { imageTier?: unknown }).imageTier).toBeUndefined()

    // 验证序列化为 wire contents
    const contents = partsToMessageContents(submittedPayload)
    expect(contents).toEqual([
      { type: 'TEXT', text: 'check images' },
      { type: 'ATTACHMENT', uploadId: expect.any(String), imageTier: '1080P' },
      { type: 'ATTACHMENT', uploadId: expect.any(String) },
    ])
  })
})
