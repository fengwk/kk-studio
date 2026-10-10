import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef, useState, type RefObject } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  ThreadComposer,
  type ComposerPreviewReadiness,
  type ThreadComposerHandle,
  type ThreadComposerProps,
} from '@/features/ai/runtime/thread-panel/ThreadComposer'
import {
  createAttachmentPart,
  createTextPart,
  slashQueryOf,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  filterThreadCommands,
  threadCommandsForTarget,
  THREAD_COMMANDS,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import { firstEnabledCommandIndex } from '@/features/ai/runtime/thread-panel/thread-command-navigation'
import { storeUnknownUploads } from '@/features/ai/composer/composer-draft'
import type { HashFile, StorageService } from '@/features/ai/composer'

type StorageUploadDTO = Awaited<ReturnType<StorageService['reserveUpload']>>

/** 预览用例的 storage fake：reserve 分配 up-1，complete 原样返回同一服务端句柄。 */
function fakePreviewStorage() {
  let counter = 0
  const reserveUpload = vi.fn(async (): Promise<StorageUploadDTO> => {
    counter += 1
    const id = `up-${counter}`
    return {
      id,
      state: 'PENDING' as const,
      blobId: null,
      presignedPut: { method: 'PUT' as const, url: `https://s3.test/${id}`, headers: {} },
      expiresAt: null,
    }
  })
  const completeUpload = vi.fn(async (uploadId: string): Promise<StorageUploadDTO> => ({
    id: uploadId,
    state: 'READY',
    blobId: `blob-${uploadId}`,
    presignedPut: null,
    expiresAt: null,
  }))
  const deleteUpload = vi.fn(async () => undefined)
  const service = {
    reserveUpload,
    completeUpload,
    deleteUpload,
    getBlobDownloadUrl: vi.fn(async () => ({ url: 'https://s3.test/orig', expiresAt: null })),
    getBlobPreviewUrl: vi.fn(async () => ({ url: 'https://s3.test/prev', expiresAt: null })),
    uploadFile: vi.fn(async () => undefined),
  } as unknown as StorageService
  return { service, deleteUpload }
}

const previewHashFile: HashFile = vi.fn(async () => 'a'.repeat(64))

/** 预览就绪 Harness：受控草稿 + 暴露 composer ref，便于直接调用 preparePreview。 */
function PreviewHarness({
  handleRef,
  initialParts = [],
  onSubmit = vi.fn(),
  onPreviewReadinessChange,
  service,
}: {
  handleRef: RefObject<ThreadComposerHandle | null>
  initialParts?: ComposerPart[]
  onSubmit?: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  onPreviewReadinessChange?: (readiness: ComposerPreviewReadiness) => void
  service?: StorageService
}) {
  const [parts, setParts] = useState<ComposerPart[]>(initialParts)
  return (
    <ThreadComposer
      ref={handleRef}
      parts={parts}
      pending={false}
      disabled={false}
      onPartsChange={setParts}
      onSubmit={onSubmit}
      onCommand={vi.fn()}
      onPreviewReadinessChange={onPreviewReadinessChange}
      storageService={service}
      hashFile={previewHashFile}
    />
  )
}

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
      'shell',
    ])
    // `/session`（全局 Session 重绑定）已彻底移除，不再出现在稳定命令表中。
    expect(THREAD_COMMANDS.some((c) => c.id === 'session')).toBe(false)
    // 命令可用性只由目标 kind 与显式能力决定，不再接受 owner 选项。
    const blank = threadCommandsForTarget({ kind: 'NEW_SESSION_DRAFT' })
    expect(blank.map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    // 空面板还没有 Thread，因此 `/history`/`/stop`/`/new`/`/debug`/`/compact`
    // 不可用，而 `/thread`（仅切换面板）、`/shortcuts` 与环境级 `/shell` 保持可用。
    expect(blank.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'yolo',
      'models',
      'upload',
      'shortcuts',
      'shell',
    ])
    expect(blank.find((c) => c.id === 'new')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'history')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'debug')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'subagent')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'compact')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'rename-session')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'rename-thread')?.disabled).toBe(true)
    expect(threadCommandsForTarget({ kind: 'BOUND_THREAD' }).every((c) => !c.disabled)).toBe(true)
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
    // 原 owner 裁剪（受控 Issue 线程隐藏导航命令）已删除：既有 Thread 的
    // 命令可用性只由目标 kind 与显式能力选项决定，owner 不参与裁剪。
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
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'high' },
          models: [
            {
              providerName: 'minimax',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'high' }],
              },
            },
          ],
          yoloEnabled: false,
          onModelChange: vi.fn(),
          onYoloChange: vi.fn(),
        }}
      />,
    )

    const dock = container.querySelector('.thread-dock')
    expect(dock).not.toBeNull()
    expect(dock?.querySelector('.composer-editor')).not.toBeNull()
    expect(dock?.querySelector('.thread-dock-controls')).not.toBeNull()
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('Default')
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toHaveTextContent(
      'minimax/MiniMax · high',
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
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          models: [
            {
              providerName: 'minimax',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'high' }],
              },
            },
            {
              providerName: 'openai',
              name: 'gpt',
              config: {
                defaultVariant: 'fast',
                variants: [{ id: 'fast' }, { id: 'quality' }],
              },
            },
          ],
          yoloEnabled: false,
          onModelChange,
          onYoloChange,
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
    expect(screen.queryByRole('option', { name: 'minimax/MiniMax' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'openai/gpt' }))
    expect(screen.getByRole('listbox', { name: 'Variant 选项' })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'quality' }))
    expect(onModelChange).toHaveBeenCalledWith({
      providerName: 'openai',
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
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          models: [
            {
              providerName: 'minimax',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }],
              },
            },
          ],
          yoloEnabled: false,
          onModelChange: vi.fn(),
          onYoloChange: vi.fn(),
        }}
      />,
    )
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^models/ }))
    expect(screen.getByRole('searchbox', { name: '搜索模型' })).toHaveFocus()
    expect(screen.getByRole('listbox', { name: 'Model 选项' })).toBeInTheDocument()
  })

  describe('ThreadComposer preview readiness', () => {
    it('reports a not-ready reason for empty, slash, goal, read-only and pending-upload drafts', () => {
      // 测试意图：预览入口已上移到 Debug 标题，Composer 只上报 readiness；
      // 空草稿/slash/goal/只读/上传未就绪各自上报唯一 reason，且 not-ready 时 preparePreview 一律返回 null。
      const scope = 'preview-reason-scope'
      const localId = 'loc-pending-preview'
      storeUnknownUploads(scope, [
        {
          localId,
          uploadId: 'up-pending-preview',
          filename: 'pending.png',
          mediaType: 'image/png',
          sizeBytes: 1024,
          sha256: 'sha-pending',
        },
      ])
      const handleRef = createRef<ThreadComposerHandle>()
      const onPreviewReadinessChange = vi.fn()
      const lastReadiness = () => onPreviewReadinessChange.mock.calls.at(-1)?.[0]
      const composer = (props: Partial<ThreadComposerProps>) => (
        <ThreadComposer
          ref={handleRef}
          parts={[]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
          onPreviewReadinessChange={onPreviewReadinessChange}
          {...props}
        />
      )

      const { rerender } = render(composer({}))
      expect(lastReadiness()).toEqual({ canPreview: false, reason: 'EMPTY_DRAFT' })
      expect(handleRef.current?.preparePreview()).toBeNull()

      rerender(composer({ parts: [createTextPart('/models')] }))
      expect(lastReadiness()).toEqual({ canPreview: false, reason: 'SLASH_COMMAND' })
      expect(handleRef.current?.preparePreview()).toBeNull()

      rerender(composer({ parts: [createTextPart('/goal Ship feature')] }))
      expect(lastReadiness()).toEqual({ canPreview: false, reason: 'GOAL_COMMAND' })
      expect(handleRef.current?.preparePreview()).toBeNull()

      // 只读交互作用域：disabled 优先级最高，即便草稿有内容也不可预览。
      rerender(composer({ parts: [createTextPart('read only draft')], disabled: true }))
      expect(lastReadiness()).toEqual({ canPreview: false, reason: 'COMPOSER_DISABLED' })
      expect(handleRef.current?.preparePreview()).toBeNull()

      // complete_unknown 上传不算 ready，附件草稿必须等待上传完成。
      rerender(composer({
        parts: [createTextPart('draft with file'), createAttachmentPart(localId, 'pending.png')],
        scope,
      }))
      expect(lastReadiness()).toEqual({ canPreview: false, reason: 'UPLOADS_PENDING' })
      expect(handleRef.current?.preparePreview()).toBeNull()
      // 预览入口已移出 Composer：组件内不再渲染任何预览按钮。
      expect(screen.queryByRole('button', { name: '预览请求' })).not.toBeInTheDocument()
    })

    it('fails closed when the DOM draft has not reached the controlled parts yet', () => {
      // 测试意图：不能把旧受控载荷与新 DOM 草稿拼成一次预览；同步后等待下一次 render。
      const handleRef = createRef<ThreadComposerHandle>()
      const onPartsChange = vi.fn()
      const onSubmit = vi.fn()
      render(
        <ThreadComposer
          ref={handleRef}
          parts={[createTextPart('old draft')]}
          pending={false}
          disabled={false}
          onPartsChange={onPartsChange}
          onSubmit={onSubmit}
          onCommand={vi.fn()}
        />,
      )
      const editor = screen.getByLabelText('给 AI 发送消息')
      editor.textContent = 'new unsynchronized draft'
      expect(handleRef.current?.preparePreview()).toBeNull()
      expect(onPartsChange).toHaveBeenCalledWith([
        expect.objectContaining({ type: 'text', text: 'new unsynchronized draft' }),
      ])
      expect(onSubmit).not.toHaveBeenCalled()
    })

    it('previews a ready draft with server upload handles without clearing, committing or consuming it', async () => {
      // 测试意图：ready 时 preparePreview 返回 payload（localId 解析为服务端 uploadId）与 localDraft（保留 localId）；
      // 预览只读不清空草稿、不触发提交 commit，重复调用也不会消费或释放上传句柄。
      const handleRef = createRef<ThreadComposerHandle>()
      const onSubmit = vi.fn()
      const onPreviewReadinessChange = vi.fn()
      const { service, deleteUpload } = fakePreviewStorage()
      render(
        <PreviewHarness
          handleRef={handleRef}
          initialParts={[createTextPart('preview me')]}
          onSubmit={onSubmit}
          onPreviewReadinessChange={onPreviewReadinessChange}
          service={service}
        />,
      )

      const editor = screen.getByLabelText('给 AI 发送消息')
      // 编辑器内附件 pill：与附件 strip 里的同名展示区分开，只断言草稿本体。
      const pillOf = () => editor.querySelector('span.composer-pill[data-filename="note.txt"]')
      fireEvent.paste(editor, {
        clipboardData: {
          files: [new File([new Uint8Array(64)], 'note.txt', { type: 'text/plain' })],
          getData: () => '',
        },
      })
      await waitFor(() => expect(pillOf()).not.toBeNull())
      await waitFor(() =>
        expect(onPreviewReadinessChange.mock.calls.at(-1)?.[0]).toEqual({ canPreview: true, reason: null }),
      )

      const localId = editor
        .querySelector<HTMLElement>('span[data-part-type="attachment"]')
        ?.dataset.uploadId
      expect(localId).toBeTruthy()

      const prepared = handleRef.current?.preparePreview()
      expect(prepared).not.toBeNull()
      expect(prepared?.payload).toEqual([
        expect.objectContaining({ type: 'text', text: 'preview me' }),
        expect.objectContaining({ type: 'attachment', uploadId: 'up-1', filename: 'note.txt' }),
      ])
      expect(prepared?.localDraft).toEqual([
        expect.objectContaining({ type: 'text', text: 'preview me' }),
        expect.objectContaining({ type: 'attachment', uploadId: localId, filename: 'note.txt' }),
      ])

      // 预览不改变草稿：文本与 pill 仍在，提交通道未被触发。
      expect(onSubmit).not.toHaveBeenCalled()
      expect(editor.textContent).toContain('preview me')
      expect(pillOf()).not.toBeNull()

      // 重复 prepare 幂等：不 commit、不消费/释放句柄，readiness 依旧 ready。
      const preparedAgain = handleRef.current?.preparePreview()
      expect(preparedAgain?.payload).toEqual(prepared?.payload)
      expect(deleteUpload).not.toHaveBeenCalled()
      expect(onSubmit).not.toHaveBeenCalled()
      expect(onPreviewReadinessChange.mock.calls.at(-1)?.[0]).toEqual({ canPreview: true, reason: null })
      expect(pillOf()).not.toBeNull()
    })
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
      expect(await screen.findByText('2.0 KB')).toBeInTheDocument()
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
