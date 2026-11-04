import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { SessionFilesDialog } from '@/features/chat/session-files/SessionFilesDialog'
import {
  sessionFileService,
  type SessionFileOwner,
  type SessionFileTextWindow,
} from '@/shared/api/session-file-service'
import type {
  HarnessSessionFileContentDTO,
  HarnessSessionFileListDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/session-file-service', () => ({
  sessionFileService: {
    listSessionFiles: vi.fn(),
    readSessionFileContent: vi.fn(),
    downloadSessionFile: vi.fn(),
  },
}))

const SESSION_ID = '11111111-2222-4333-8444-555555555555'
const OWNER: SessionFileOwner = { kind: 'CHAT', id: 'a1000000-0000-4000-8000-000000000001' }
const SESSION_ROOT = `vfs:/sessions/${SESSION_ID}/`

function listing(path: string, entries: HarnessSessionFileListDTO['entries']): HarnessSessionFileListDTO {
  return { sessionId: SESSION_ID, path, entries }
}

const ROOT_LISTING = listing(SESSION_ROOT, [
  { name: 'docs/', path: 'docs/', directory: true },
  { name: 'notes.txt', path: 'notes.txt', directory: false, sizeBytes: '42', revision: 1, updatedAt: '1790812800000' },
  { name: 'photo.png', path: 'photo.png', directory: false, sizeBytes: '68', revision: 1, updatedAt: '1790812800000' },
  { name: 'binary.bin', path: 'binary.bin', directory: false, sizeBytes: '4', revision: 1, updatedAt: '1790812800000' },
  { name: 'missing.txt', path: 'missing.txt', directory: false, sizeBytes: '1', revision: 1, updatedAt: '1790812800000' },
])

const DOCS_LISTING = listing(`${SESSION_ROOT}docs/`, [
  { name: 'readme.md', path: 'docs/readme.md', directory: false, sizeBytes: '12', revision: 1, updatedAt: '1790812800000' },
])

function content(text: string, path: string): HarnessSessionFileContentDTO {
  return {
    sessionId: SESSION_ID,
    path,
    sizeBytes: '64',
    revision: 2,
    updatedAt: '1790812800000',
    text,
  }
}

const FIRST_WINDOW = content([
  `path: ${SESSION_ROOT}notes.txt`,
  'ends_with_newline: yes',
  'range: 1:1-2:9',
  'truncated: yes',
  'truncation_reason: line_limit',
  'next: 3:1',
  '',
  '1|alpha',
  '2|beta',
  '',
  '[TRUNCATED: More file content remains. Next position: line 3, column 1.]',
].join('\n'), `${SESSION_ROOT}notes.txt`)

const SECOND_WINDOW = content([
  `path: ${SESSION_ROOT}notes.txt`,
  'ends_with_newline: no',
  'range: 3:1-3:7',
  '',
  '3|gamma',
].join('\n'), `${SESSION_ROOT}notes.txt`)

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onClose = vi.fn()
  render(
    <QueryClientProvider client={client}>
      <SessionFilesDialog sessionId={SESSION_ID} owner={OWNER} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { onClose }
}

describe('SessionFilesDialog', () => {
  beforeEach(() => {
    vi.mocked(sessionFileService.listSessionFiles).mockReset()
    vi.mocked(sessionFileService.readSessionFileContent).mockReset()
    vi.mocked(sessionFileService.downloadSessionFile).mockReset()

    vi.mocked(sessionFileService.listSessionFiles).mockImplementation(async (_sessionId, _owner, path) => {
      if (path == null) {
        return ROOT_LISTING
      }
      if (path === `${SESSION_ROOT}docs/`) {
        return DOCS_LISTING
      }
      throw new ApiError('No such directory', 404, 'NOT_FOUND')
    })
    vi.mocked(sessionFileService.readSessionFileContent).mockImplementation(
      async (_sessionId, _owner, path, window?: SessionFileTextWindow) => {
        if (path.endsWith('/missing.txt')) {
          throw new ApiError('The requested resource was not found.', 404, 'NOT_FOUND', {
            detail: `No such file: ${path}`,
          })
        }
        if (path.endsWith('/binary.bin')) {
          throw new ApiError('HTTP error (status 422).', 422, 'UNPROCESSABLE_CONTENT', {
            detail: 'file appears to be binary',
          })
        }
        return window?.offset === 3 ? SECOND_WINDOW : FIRST_WINDOW
      },
    )
    vi.mocked(sessionFileService.downloadSessionFile).mockResolvedValue({
      blob: new Blob(['image'], { type: 'image/png' }),
      fileName: 'photo.png',
    })
  })

  /**
   * 测试意图：会话根目录不带 path 参数；目录条目下钻时把会话内相对路径
   * 拼成 vfs 坐标，上一级返回根目录。
   */
  it('browses the session root and enters directories', async () => {
    const user = userEvent.setup()
    renderDialog()

    expect(await screen.findByTitle('docs/')).toBeVisible()
    expect(screen.getByTitle('notes.txt')).toBeVisible()
    expect(sessionFileService.listSessionFiles).toHaveBeenCalledWith(SESSION_ID, OWNER, undefined)

    await user.click(screen.getByTitle('docs/'))
    expect(await screen.findByTitle('docs/readme.md')).toBeVisible()
    expect(sessionFileService.listSessionFiles).toHaveBeenLastCalledWith(
      SESSION_ID,
      OWNER,
      `${SESSION_ROOT}docs/`,
    )

    await user.click(screen.getByTestId('session-files-up'))
    expect(await screen.findByTitle('notes.txt')).toBeVisible()
    expect(sessionFileService.listSessionFiles).toHaveBeenLastCalledWith(SESSION_ID, OWNER, undefined)
  })

  /**
   * 测试意图：文本预览按服务端窗口读取，下一页使用返回的 next 位置，
   * 上一页回到先前窗口，不把截断标记混进正文。
   */
  it('previews text windows and pages through the server next position', async () => {
    const user = userEvent.setup()
    renderDialog()

    await user.click(await screen.findByTitle('notes.txt'))

    await waitFor(() => {
      expect(screen.getByTestId('session-file-text')).toHaveTextContent('1|alpha')
    })
    expect(screen.getByTestId('session-file-text')).toHaveTextContent('2|beta')
    expect(screen.queryByText(/TRUNCATED/)).toBeNull()
    expect(screen.getByTestId('session-files-range')).toHaveTextContent('行 1:1-2:9')
    expect(sessionFileService.readSessionFileContent).toHaveBeenCalledWith(
      SESSION_ID,
      OWNER,
      `${SESSION_ROOT}notes.txt`,
      { offset: 1, columnOffset: 1, limit: 500 },
    )

    await user.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => {
      expect(screen.getByTestId('session-file-text')).toHaveTextContent('3|gamma')
    })
    expect(sessionFileService.readSessionFileContent).toHaveBeenLastCalledWith(
      SESSION_ID,
      OWNER,
      `${SESSION_ROOT}notes.txt`,
      { offset: 3, columnOffset: 1, limit: 500 },
    )

    await user.click(screen.getByRole('button', { name: '上一页' }))
    await waitFor(() => {
      expect(screen.getByTestId('session-file-text')).toHaveTextContent('1|alpha')
    })
  })

  /**
   * 测试意图：404 与不可读文本使用稳定分类文案，并分行展示后端原因；
   * 不可读文件仍保留下载入口，不提供任何写操作。
   */
  it('reports missing and unreadable files with backend details', async () => {
    const user = userEvent.setup()
    renderDialog()

    await user.click(await screen.findByTitle('missing.txt'))
    expect(await screen.findByText('文件不存在或无权访问')).toBeVisible()
    expect(screen.getByText(`No such file: ${SESSION_ROOT}missing.txt`)).toBeVisible()

    await user.click(screen.getByTitle('binary.bin'))
    expect(await screen.findByText('此文件不能作为文本预览')).toBeVisible()
    expect(screen.getByText('file appears to be binary')).toBeVisible()
    expect(within(screen.getByLabelText('会话文件预览')).getByRole('button', { name: '下载' })).toBeVisible()
  })

  /**
   * 测试意图：图片文件经下载字节生成本地预览 URL；预览区下载按钮使用
   * vfs 文件坐标并触发浏览器保存，而不是再次读取文本窗口。
   */
  it('previews images and downloads the selected file', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:session-file')
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    renderDialog()

    await user.click(await screen.findByTitle('photo.png'))

    const image = await screen.findByRole('img', { name: 'photo.png' })
    expect(image).toHaveAttribute('src', 'blob:session-file')
    expect(sessionFileService.readSessionFileContent).not.toHaveBeenCalled()

    await user.click(
      within(screen.getByLabelText('会话文件预览')).getByRole('button', { name: '下载' }),
    )
    await waitFor(() => {
      expect(sessionFileService.downloadSessionFile).toHaveBeenCalledWith(
        SESSION_ID,
        OWNER,
        `${SESSION_ROOT}photo.png`,
      )
    })
    expect(click).toHaveBeenCalledTimes(1)

    createObjectURL.mockRestore()
    revokeObjectURL.mockRestore()
    click.mockRestore()
  })

  it('closes from the footer action', async () => {
    const user = userEvent.setup()
    const { onClose } = renderDialog()

    await user.click(await screen.findByTestId('session-files-close'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
