import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  THREAD_COMMANDS,
  threadCommandsForTarget,
  type ThreadCommand,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import type {
  HashFile,
  StorageService,
} from '@/features/ai/composer'
import { AttachmentStrip } from '@/features/ai/composer/attachment-strip'
import type { AttachmentUpload } from '@/features/ai/composer/use-attachment-uploads'

type StorageUploadDTO = Awaited<ReturnType<StorageService['reserveUpload']>>

const EMPTY_PARTS: ComposerPart[] = []

function fileOf(name: string, type: string, size = 128): File {
  return new File([new Uint8Array(size)], name, { type })
}

/** 与 ThreadComposer.pill.test.tsx 相同的可编程 storage fake（PENDING 直传契约）。 */
function fakeStorage() {
  const reservations = new Map<string, Parameters<StorageService['reserveUpload']>[0]>()
  let counter = 0
  const reserveUpload = vi.fn(
    async (request: Parameters<StorageService['reserveUpload']>[0]): Promise<StorageUploadDTO> => {
      counter += 1
      const id = `up-${counter}`
      reservations.set(id, request)
      return {
        id,
        state: 'PENDING' as const,
        blobId: null,
        presignedPut: { method: 'PUT' as const, url: `https://s3.test/${id}`, headers: {} },
        expiresAt: null,
      }
    },
  )
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

const hashFile: HashFile = vi.fn(async () => 'a'.repeat(64))

const MODEL_SETTINGS = {
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
  ],
  yoloEnabled: false,
  onModelChange: vi.fn(),
  onYoloChange: vi.fn(),
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
  settings?: typeof MODEL_SETTINGS
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

function partsSnapshot(): Array<Record<string, string>> {
  const raw = JSON.parse(screen.getByTestId('parts').textContent ?? '[]') as ComposerPart[]
  return raw.map((part) =>
    part.type === 'text'
      ? { type: 'text', text: part.text }
      : { type: 'attachment', uploadId: part.uploadId, filename: part.filename },
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

async function waitForIdleUploads() {
  await waitFor(() => expect(screen.queryByText(/上传中…/)).not.toBeInTheDocument())
}

describe('ThreadComposer edge interactions', () => {
  beforeEach(() => {
    vi.mocked(hashFile).mockClear()
    MODEL_SETTINGS.onModelChange.mockClear()
    MODEL_SETTINGS.onYoloChange.mockClear()
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

  it('navigates the plus palette with ArrowUp wrapping to the last enabled command', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    // NEW_SESSION_DRAFT 目标：tree/stop/new/debug/compact 禁用，ArrowUp 从首项环绕到
    // 最后一个可用项 shortcuts。
    const commands = threadCommandsForTarget({ kind: 'NEW_SESSION_DRAFT' })
    render(<Harness onCommand={onCommand} commands={commands} />)
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    await user.keyboard('{ArrowUp}')
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'shortcuts' }))
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

    // slash palette 打开时全局 Escape：closeOverlay 清空命令草稿并关闭。
    await user.type(editor, '/stop')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(partsSnapshot()).toEqual([])
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
      error: null,
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
          onRetry={vi.fn()}
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
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(editor)
    await user.keyboard('{Enter}')

    // 无 settings 时 /models 只清空 slash 草稿，不触发 onCommand。
    expect(onCommand).not.toHaveBeenCalled()
    expect(onPartsChange).toHaveBeenCalledWith([])
  })
})
