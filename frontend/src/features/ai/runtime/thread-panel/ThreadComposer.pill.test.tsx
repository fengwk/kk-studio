import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import {
  createAttachmentPart,
  type ComposerPart,
  type HashFile,
  type StorageService,
} from '@/features/ai/composer'

type StorageUploadDTO = Awaited<ReturnType<StorageService['reserveUpload']>>

function fileOf(name: string, type: string, size = 128): File {
  return new File([new Uint8Array(size)], name, { type })
}

/**
 * 可编程的 storage fake，镜像后端契约：
 * - reserve 返回 StorageUploadDTO；默认 PENDING（携带 presignedPut，需直传）；
 * - complete 返回同一 DTO（id 不变），upload 句柄绝不变成 blobId；
 * - DELETE 目标始终是 upload 句柄。
 */
function fakeStorage(overrides: {
  reserve?: (request: Parameters<StorageService['reserveUpload']>[0]) => StorageUploadDTO
  failReserveTimes?: number
} = {}) {
  const reservations = new Map<string, Parameters<StorageService['reserveUpload']>[0]>()
  let counter = 0
  let reserveFailures = overrides.failReserveTimes ?? 0
  const reserveUpload = vi.fn(
    async (request: Parameters<StorageService['reserveUpload']>[0]): Promise<StorageUploadDTO> => {
      if (reserveFailures > 0) {
        reserveFailures -= 1
        throw new Error('reserve unavailable')
      }
      counter += 1
      const id = `up-${counter}`
      const result = overrides.reserve
        ? overrides.reserve(request)
        : {
            id,
            state: 'PENDING' as const,
            blobId: null,
            presignedPut: { method: 'PUT' as const, url: `https://s3.test/${id}`, headers: {} },
            expiresAt: null,
          }
      reservations.set(result.id, request)
      return result
    },
  )
  const completeUpload = vi.fn(async (uploadId: string): Promise<StorageUploadDTO> => {
    void reservations.get(uploadId)
    // 同一句柄：complete 不产生新 id，blobId 是落库后的持久资源（客户端不使用）。
    return {
      id: uploadId,
      state: 'READY',
      blobId: `blob-${uploadId}`,
      presignedPut: null,
      expiresAt: null,
    }
  })
  const deleteUpload = vi.fn(async () => undefined)
  const getBlobOriginalUrl = vi.fn(async () => ({ url: 'https://s3.test/orig', expiresAt: null }))
  const getBlobPreviewUrl = vi.fn(async () => ({ url: 'https://s3.test/prev', expiresAt: null }))
  const uploadFile = vi.fn(async () => undefined)
  const service = {
    reserveUpload,
    completeUpload,
    deleteUpload,
    getBlobOriginalUrl,
    getBlobPreviewUrl,
    uploadFile,
  } as unknown as StorageService
  return { service, reserveUpload, completeUpload, deleteUpload, uploadFile }
}

const hashFile: HashFile = vi.fn(async () => 'a'.repeat(64))

/** 稳定空数组：避免默认参数每次渲染生成新引用触发同步 effect 死循环。 */
const EMPTY_PARTS: ComposerPart[] = []

function Harness({
  service,
  initialParts = EMPTY_PARTS,
  onSubmit = vi.fn(),
  hash = hashFile,
}: {
  service: StorageService
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

function partsSnapshot(): Array<Record<string, string>> {
  const raw = JSON.parse(screen.getByTestId('parts').textContent ?? '[]') as ComposerPart[]
  return raw.map((part) =>
    part.type === 'text'
      ? { type: 'text', text: part.text }
      : { type: 'attachment', uploadId: part.uploadId, filename: part.filename },
  )
}

async function pasteFiles(editor: HTMLElement, ...files: File[]) {
  fireEvent.paste(editor, { clipboardData: { files, getData: () => '' } })
  await waitFor(() => expect(document.querySelectorAll('.composer-pill').length).toBe(files.length))
}

async function waitForIdleUploads() {
  await waitFor(() => expect(screen.queryByText(/上传中…/)).not.toBeInTheDocument())
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

  it('serializes ordered text -> pill -> text parts from typing and paste', async () => {
    const { service } = fakeStorage()
    render(<Harness service={service} />)
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
    expect(pill).toHaveTextContent('[photo.png](upload)')
    expect(document.querySelector('.attachment-tile')).toBeNull()
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

  it('inserts pasted pills at the caret between text', async () => {
    const { service } = fakeStorage()
    render(<Harness service={service} />)
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
    render(<Harness service={service} onSubmit={onSubmit} />)
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
    expect(screen.getByText('[report.pdf (1)](upload)')).toBeInTheDocument()
    expect(screen.getByText('[report.pdf (2)](upload)')).toBeInTheDocument()
    // 提交 payload 中解析为互不相同的服务端 upload 句柄。
    await waitForIdleUploads()
    placeCaretAtEndOf(editor)
    fireEvent.keyDown(editor, { key: 'Enter' })
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const sent = onSubmit.mock.calls[0]?.[0] as ComposerPart[]
    const sentIds = sent.map((part) => part.uploadId)
    expect(sentIds[0]).toMatch(/^up-\d+$/)
    expect(sentIds[0]).not.toBe(sentIds[1])
  })

  it('gives identical-metadata files distinct handles without client dedup', async () => {
    const { service, reserveUpload } = fakeStorage()
    render(<Harness service={service} />)
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
    render(<Harness service={service} />)
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
    render(<Harness service={service} onSubmit={onSubmit} hash={gatedHash} />)
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
        expiresAt: null,
      }),
    })
    render(<Harness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await pasteFiles(editor, fileOf('photo.png', 'image/png'))
    await waitForIdleUploads()
    expect(reserveUpload).toHaveBeenCalledTimes(1)
    // READY = sha256 命中已有内容：跳过直传，直接 complete。
    expect(uploadFile).not.toHaveBeenCalled()
    expect(completeUpload).toHaveBeenCalledWith('dedup-aaaaaaaa')
  })

  it('shows upload failure, retries, and removes the reference', async () => {
    const user = userEvent.setup()
    const { service, reserveUpload, deleteUpload } = fakeStorage({ failReserveTimes: 1 })
    render(<Harness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await pasteFiles(editor, fileOf('broken.bin', 'application/octet-stream'))
    await waitFor(() => expect(screen.getByText('reserve unavailable')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /重试上传/ })).toBeInTheDocument()
    // 失败状态阻塞发送。
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()

    // 重试成功。
    await user.click(screen.getByRole('button', { name: /重试上传/ }))
    await waitFor(() => expect(reserveUpload).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('reserve unavailable')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /重试上传/ })).not.toBeInTheDocument()

    // 引用后的 X：移除全部 occurrences 并释放 upload 句柄（绝不按 blobId 删除）。
    await user.click(screen.getByRole('button', { name: /移除附件/ }))
    await waitFor(() => expect(screen.queryAllByRole('listitem')).toHaveLength(0))
    expect(partsSnapshot()).toEqual([])
    await waitFor(() => expect(deleteUpload).toHaveBeenCalledWith('up-1'))
  })

  it('creates pills from drop and the hidden file picker', async () => {
    const { service } = fakeStorage()
    render(<Harness service={service} />)
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
    render(<Harness service={service} />)
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
    expect(textPasteAllowed).toBe(true)
    // jsdom 不执行 contenteditable 的浏览器默认 paste；模拟默认插入后触发 input，
    // 验证现有 onInput 回流路径保持普通文本。
    await typeInEditor(editor, 'plain text')
    expect(partsSnapshot()).toEqual([
      expect.objectContaining({ type: 'attachment', filename: 'clipboard.png' }) as Record<string, string>,
      { type: 'text', text: 'plain text' },
    ])
  })

  it('rejects oversized video, audio and generic files with a visible error', async () => {
    const { service } = fakeStorage()
    render(<Harness service={service} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const bigVideo = new File([new Uint8Array(100 * 1024 * 1024 + 1)], 'huge.mp4', { type: 'video/mp4' })
    const bigAudio = new File([new Uint8Array(15 * 1024 * 1024 + 1)], 'huge.mp3', { type: 'audio/mpeg' })
    const bigFile = new File([new Uint8Array(30 * 1024 * 1024 + 1)], 'huge.bin', { type: 'application/octet-stream' })
    fireEvent.paste(editor, { clipboardData: { files: [bigVideo, bigAudio, bigFile], getData: () => '' } })
    await waitFor(() => expect(screen.getByText(/huge\.mp4 超过 100\.0 MB/)).toBeInTheDocument())
    expect(screen.getByText(/huge\.mp3 超过 15\.0 MB/)).toBeInTheDocument()
    expect(screen.getByText(/huge\.bin 超过 30\.0 MB/)).toBeInTheDocument()
    // 超限文件不创建 pill。
    expect(document.querySelectorAll('.composer-pill')).toHaveLength(0)
  })

  it('rebuilds a replaced same-name attachment with a fresh partId', async () => {
    const { service } = fakeStorage()
    const first = createAttachmentPart('local-1', 'report.pdf')
    const second = createAttachmentPart('local-2', 'report.pdf')
    const { rerender } = render(<Harness service={service} initialParts={[first]} />)
    const firstPill = document.querySelector('.composer-pill')
    const firstPartId = firstPill?.getAttribute('data-part-id')
    expect(firstPill?.getAttribute('data-upload-id')).toBe('local-1')

    // 同名附件 A -> B（不同 localId）：DOM 必须重建，不得保留过期 partId。
    rerender(<Harness service={service} initialParts={[second]} />)
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
    const { rerender } = render(<Harness service={service} initialParts={[first, second]} />)
    const pills = document.querySelectorAll('.composer-pill')
    expect(pills).toHaveLength(2)
    const firstPartId = pills[0]?.getAttribute('data-part-id')
    const secondPartId = pills[1]?.getAttribute('data-part-id')
    expect(firstPartId).not.toBe(secondPartId)
    expect(pills[0]?.getAttribute('data-upload-id')).toBe('up-x')

    // 替换第一个 occurrence（同句柄、新 partId）：DOM 必须重建出新的 partId。
    rerender(<Harness service={service} initialParts={[replacement, second]} />)
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
    render(<Harness service={service} initialParts={[first, second]} />)
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
    render(<Harness service={service} onSubmit={onSubmit} />)
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
})
