import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type { CloudFilesApi } from './cloud-files-api'
import { FilesPage } from './FilesPage'
import type { CloudFileSnapshotDTO, CloudNodeDTO } from './types'
import { notifyCloudFilesChanged } from './useCloudFilesInvalidation'

const ROOT_DIR_NODE: CloudNodeDTO = {
  id: '00000000-0000-0000-0000-000000000001',
  path: '/',
  name: '',
  kind: 'DIRECTORY',
  version: '0',
  blobId: null,
  mediaType: null,
  sizeBytes: null,
  sha256: null,
  revision: null,
  createdAt: null,
  updatedAt: null,
}

const TEXT_FILE_NODE: CloudNodeDTO = {
  id: '00000000-0000-0000-0000-000000000002',
  path: '/notes.md',
  name: 'notes.md',
  kind: 'TEXT',
  version: '1',
  blobId: null,
  mediaType: 'text/markdown',
  sizeBytes: '25',
  sha256: null,
  revision: '1',
  createdAt: null,
  updatedAt: null,
}

const BLOB_IMAGE_NODE: CloudNodeDTO = {
  id: '00000000-0000-0000-0000-000000000003',
  path: '/banner.png',
  name: 'banner.png',
  kind: 'BLOB',
  version: '1',
  blobId: '99999999-9999-9999-9999-999999999999',
  mediaType: 'image/png',
  sizeBytes: '4096',
  sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  revision: null,
  createdAt: null,
  updatedAt: null,
}

const ARTIFACT_NODE: CloudNodeDTO = {
  id: '00000000-0000-0000-0000-000000000004',
  path: '/.artifacts',
  name: '.artifacts',
  kind: 'DIRECTORY',
  version: '1',
  blobId: null,
  mediaType: null,
  sizeBytes: null,
  sha256: null,
  revision: null,
  createdAt: null,
  updatedAt: null,
}

function createMockApi(overrides: Partial<CloudFilesApi> = {}): CloudFilesApi {
  const defaultSnapshots: Record<string, CloudFileSnapshotDTO> = {
    '/': {
      node: ROOT_DIR_NODE,
      children: [TEXT_FILE_NODE, BLOB_IMAGE_NODE, ARTIFACT_NODE],
      text: null,
      blob: null,
    },
    '/notes.md': {
      node: TEXT_FILE_NODE,
      children: null,
      text: {
        revision: '1',
        endsWithNewline: true,
        offset: 1,
        totalLines: 1,
        nextOffset: null,
        lines: [{ lineNumber: 1, content: '# Initial Notes', truncated: false }],
      },
      blob: null,
    },
    '/banner.png': {
      node: BLOB_IMAGE_NODE,
      children: null,
      text: null,
      blob: {
        blobId: BLOB_IMAGE_NODE.blobId!,
        mediaType: BLOB_IMAGE_NODE.mediaType,
        sizeBytes: BLOB_IMAGE_NODE.sizeBytes,
        sha256: BLOB_IMAGE_NODE.sha256,
      },
    },
  }

  return {
    getFileSnapshot: vi.fn().mockImplementation((path: string) => {
      if (defaultSnapshots[path]) {
        return Promise.resolve(defaultSnapshots[path])
      }
      return Promise.reject(new Error(`Not found: ${path}`))
    }),
    saveText: vi.fn().mockResolvedValue({
      ...TEXT_FILE_NODE,
      version: '2',
      revision: '2',
    }),
    patchText: vi.fn().mockResolvedValue(undefined),
    createDirectory: vi.fn().mockResolvedValue(undefined),
    moveNode: vi.fn().mockResolvedValue(undefined),
    deleteNode: vi.fn().mockResolvedValue(undefined),
    mountBlob: vi.fn().mockResolvedValue(undefined),
    getBlobPreviewUrl: vi.fn().mockResolvedValue({
      url: 'https://storage.example.com/preview.png',
      expiresAt: null,
    }),
    getBlobDownloadUrl: vi.fn().mockResolvedValue({
      url: 'https://storage.example.com/download.png',
      expiresAt: null,
    }),
    uploadBlobFile: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

describe('FilesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders tree and hides /.artifacts in ordinary tree', async () => {
    const api = createMockApi()
    render(<FilesPage api={api} />)

    // Tree renders visible files
    expect(await screen.findByText('notes.md')).toBeInTheDocument()
    expect(screen.getByText('banner.png')).toBeInTheDocument()

    // .artifacts must NEVER be shown in tree
    expect(screen.queryByText('.artifacts')).not.toBeInTheDocument()
    expect(screen.queryByText('/.artifacts')).not.toBeInTheDocument()
  })

  it('ignores a stale directory response after a newer invalidation refresh', async () => {
    // 测试意图：迟到的旧目录响应不得覆盖较新的失效刷新结果。
    const oldNode = { ...TEXT_FILE_NODE, name: 'old.md', path: '/old.md' }
    const newNode = { ...TEXT_FILE_NODE, name: 'new.md', path: '/new.md' }
    let resolveOldRoot!: (snapshot: CloudFileSnapshotDTO) => void
    const oldRoot = new Promise<CloudFileSnapshotDTO>((resolve) => {
      resolveOldRoot = resolve
    })
    let rootReads = 0
    const api = createMockApi({
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path !== '/') {
          return Promise.reject(new Error('Not found'))
        }
        if (rootReads++ === 0) {
          return oldRoot
        }
        return Promise.resolve({
          node: ROOT_DIR_NODE,
          children: [newNode],
          text: null,
          blob: null,
        })
      }),
    })

    render(<FilesPage api={api} />)
    act(() => notifyCloudFilesChanged())

    expect(await screen.findByText('new.md')).toBeInTheDocument()
    await act(async () => {
      resolveOldRoot({
        node: ROOT_DIR_NODE,
        children: [oldNode],
        text: null,
        blob: null,
      })
      await oldRoot
    })

    expect(screen.getByText('new.md')).toBeInTheDocument()
    expect(screen.queryByText('old.md')).not.toBeInTheDocument()
  })

  it('selects a text file and displays its content in the text editor', async () => {
    const api = createMockApi()
    render(<FilesPage api={api} />)

    const notesItem = await screen.findByText('notes.md')
    fireEvent.click(notesItem)

    // Text editor is rendered with content
    const textarea = await screen.findByTestId('text-editor-textarea')
    expect(textarea).toHaveValue('# Initial Notes\n')
  })

  it('keeps an unsaved draft mounted while an invalidation refresh is pending', async () => {
    // 测试意图：全局失效刷新不得卸载当前编辑器或用服务端新内容覆盖未保存草稿。
    let notesReads = 0
    let resolveRefresh!: (snapshot: CloudFileSnapshotDTO) => void
    const refresh = new Promise<CloudFileSnapshotDTO>((resolve) => {
      resolveRefresh = resolve
    })
    const api = createMockApi({
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path === '/') {
          return Promise.resolve({
            node: ROOT_DIR_NODE,
            children: [TEXT_FILE_NODE],
            text: null,
            blob: null,
          })
        }
        if (path === '/notes.md' && notesReads++ === 0) {
          return Promise.resolve({
            node: TEXT_FILE_NODE,
            children: null,
            text: {
              revision: '1',
              endsWithNewline: true,
              offset: 1,
              totalLines: 1,
              nextOffset: null,
              lines: [{ lineNumber: 1, content: '# Initial Notes', truncated: false }],
            },
            blob: null,
          })
        }
        if (path === '/notes.md') {
          return refresh
        }
        return Promise.reject(new Error(`Not found: ${path}`))
      }),
    })
    render(<FilesPage api={api} />)

    fireEvent.click(await screen.findByText('notes.md'))
    const textarea = await screen.findByTestId('text-editor-textarea')
    fireEvent.change(textarea, { target: { value: '# Unsaved Draft' } })

    act(() => notifyCloudFilesChanged())
    await waitFor(() => expect(api.getFileSnapshot).toHaveBeenCalledTimes(4))
    expect(screen.getByTestId('text-editor-textarea')).toBe(textarea)
    expect(textarea).toHaveValue('# Unsaved Draft')

    await act(async () => {
      resolveRefresh({
        node: { ...TEXT_FILE_NODE, version: '2' },
        children: null,
        text: {
          revision: '2',
          endsWithNewline: true,
          offset: 1,
          totalLines: 1,
          nextOffset: null,
          lines: [{ lineNumber: 1, content: '# Server Update', truncated: false }],
        },
        blob: null,
      })
      await refresh
    })
    expect(screen.getByTestId('text-editor-textarea')).toBe(textarea)
    expect(textarea).toHaveValue('# Unsaved Draft')
    expect(screen.getByText('Rev: 1')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '保存文件' }))
    await waitFor(() => {
      expect(api.saveText).toHaveBeenCalledWith({
        path: '/notes.md',
        content: '# Unsaved Draft',
        expectedRevision: '1',
      })
    })
    expect(await screen.findByText('Rev: 2')).toBeInTheDocument()
  })

  it('discards a conflicted draft by loading the latest complete text', async () => {
    // 测试意图：用户明确放弃冲突草稿时，应以最新服务端正文和 revision 重置编辑器。
    let notesReads = 0
    const conflictError = new ApiError(
      'Version conflict',
      409,
      'CLOUD_VERSION_CONFLICT',
      { reason: 'CLOUD_REVISION_CONFLICT', detail: 'revision changed' },
    )
    const api = createMockApi({
      saveText: vi.fn().mockRejectedValue(conflictError),
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path === '/') {
          return Promise.resolve({
            node: ROOT_DIR_NODE,
            children: [TEXT_FILE_NODE],
            text: null,
            blob: null,
          })
        }
        if (path === '/notes.md' && notesReads++ === 0) {
          return Promise.resolve({
            node: TEXT_FILE_NODE,
            children: null,
            text: {
              revision: '1',
              endsWithNewline: true,
              offset: 1,
              totalLines: 1,
              nextOffset: null,
              lines: [{ lineNumber: 1, content: '# Initial Notes', truncated: false }],
            },
            blob: null,
          })
        }
        return Promise.resolve({
          node: { ...TEXT_FILE_NODE, version: '2' },
          children: null,
          text: {
            revision: '2',
            endsWithNewline: true,
            offset: 1,
            totalLines: 1,
            nextOffset: null,
            lines: [{ lineNumber: 1, content: '# Server Update', truncated: false }],
          },
          blob: null,
        })
      }),
    })
    render(<FilesPage api={api} />)

    fireEvent.click(await screen.findByText('notes.md'))
    const textarea = await screen.findByTestId('text-editor-textarea')
    fireEvent.change(textarea, { target: { value: '# Conflicted Draft' } })
    fireEvent.click(screen.getByRole('button', { name: '保存文件' }))
    fireEvent.click(await screen.findByText('重新加载（放弃草稿）'))

    await waitFor(() => {
      expect(textarea).toHaveValue('# Server Update\n')
      expect(screen.getByText('Rev: 2')).toBeInTheDocument()
    })
  })

  it('keeps a partial text window read-only to prevent destructive replacement', async () => {
    // 测试意图：服务端分段或截断文本只能预览，不能被完整写接口覆盖。
    const api = createMockApi({
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path === '/') {
          return Promise.resolve({
            node: ROOT_DIR_NODE,
            children: [TEXT_FILE_NODE],
            text: null,
            blob: null,
          })
        }
        return Promise.resolve({
          node: TEXT_FILE_NODE,
          children: null,
          text: {
            revision: '1',
            endsWithNewline: false,
            offset: 1,
            totalLines: 2,
            nextOffset: 2,
            lines: [{ lineNumber: 1, content: 'preview only', truncated: false }],
          },
          blob: null,
        })
      }),
    })
    render(<FilesPage api={api} />)

    fireEvent.click(await screen.findByText('notes.md'))
    const textarea = await screen.findByTestId('text-editor-textarea')
    expect(textarea).toHaveAttribute('readonly')
    expect(screen.getByText(/当前仅显示分段预览/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存文件' })).toBeDisabled()
    fireEvent.change(textarea, { target: { value: 'destructive replacement' } })
    expect(textarea).toHaveValue('preview only')
    expect(api.saveText).not.toHaveBeenCalled()
  })

  it('proves draft survives 409 CAS conflict without being overwritten', async () => {
    const conflictError = new ApiError(
      'Revision conflict',
      409,
      'CLOUD_REVISION_CONFLICT',
      { reason: 'CLOUD_REVISION_CONFLICT', detail: 'expected=1 actual=2' },
    )

    const api = createMockApi({
      saveText: vi.fn().mockRejectedValue(conflictError),
    })

    render(<FilesPage api={api} />)

    const notesItem = await screen.findByText('notes.md')
    fireEvent.click(notesItem)

    const textarea = (await screen.findByTestId(
      'text-editor-textarea',
    )) as HTMLTextAreaElement
    expect(textarea).toHaveValue('# Initial Notes\n')

    // User edits draft
    fireEvent.change(textarea, { target: { value: '# My Unsaved Draft Content' } })
    expect(textarea.value).toBe('# My Unsaved Draft Content')

    // User clicks Save
    const saveButton = screen.getByRole('button', { name: '保存文件' })
    fireEvent.click(saveButton)

    // Conflict banner appears
    const conflictBanner = await screen.findByTestId('conflict-banner')
    expect(conflictBanner).toBeInTheDocument()
    expect(conflictBanner).toHaveTextContent('CLOUD_REVISION_CONFLICT')

    // DRAFT MUST SURVIVE: verify textarea still holds user draft!
    expect(textarea.value).toBe('# My Unsaved Draft Content')

    // Conflict banner offers reload (discard) or retry (overwrite)
    expect(screen.getByText('重新加载（放弃草稿）')).toBeInTheDocument()
    expect(screen.getByText('使用当前草稿重试保存')).toBeInTheDocument()
  })

  it('proves stale async responses cannot replace newer selection', async () => {
    let resolveFirstSnapshot!: (value: CloudFileSnapshotDTO) => void
    const firstPromise = new Promise<CloudFileSnapshotDTO>((resolve) => {
      resolveFirstSnapshot = resolve
    })

    const secondNode: CloudNodeDTO = {
      id: '00000000-0000-0000-0000-000000000005',
      path: '/second.txt',
      name: 'second.txt',
      kind: 'TEXT',
      version: '1',
      blobId: null,
      mediaType: 'text/plain',
      sizeBytes: '10',
      sha256: null,
      revision: '1',
      createdAt: null,
      updatedAt: null,
    }

    const api = createMockApi({
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path === '/') {
          return Promise.resolve({
            node: ROOT_DIR_NODE,
            children: [TEXT_FILE_NODE, secondNode],
            text: null,
            blob: null,
          })
        }
        if (path === '/notes.md') {
          // Slow response for first file
          return firstPromise
        }
        if (path === '/second.txt') {
          // Fast response for second file
          return Promise.resolve({
            node: secondNode,
            children: null,
            text: {
              revision: '1',
              endsWithNewline: false,
              offset: 1,
              totalLines: 1,
              nextOffset: null,
              lines: [{ lineNumber: 1, content: 'Second File Content', truncated: false }],
            },
            blob: null,
          })
        }
        return Promise.reject(new Error(`Not found: ${path}`))
      }),
    })

    render(<FilesPage api={api} />)

    // Wait for tree to load
    await screen.findByText('notes.md')
    const secondItem = screen.getByText('second.txt')

    // 1. Click first file (/notes.md) - slow
    fireEvent.click(screen.getByText('notes.md'))

    // 2. Immediately click second file (/second.txt) - fast
    fireEvent.click(secondItem)

    // Second file loads and displays
    const textarea = await screen.findByTestId('text-editor-textarea')
    expect(textarea).toHaveValue('Second File Content')

    // 3. Now the slow first request finally resolves
    resolveFirstSnapshot({
      node: TEXT_FILE_NODE,
      children: null,
      text: {
        revision: '1',
        endsWithNewline: true,
        offset: 1,
        totalLines: 1,
        nextOffset: null,
        lines: [{ lineNumber: 1, content: 'STALE First File Content', truncated: false }],
      },
      blob: null,
    })

    // Wait a tick and verify stale response did NOT replace the second selection!
    await waitFor(() => {
      expect(textarea).toHaveValue('Second File Content')
    })
  })

  it('fetches media preview URLs lazily on active blob render and download URL on demand', async () => {
    const api = createMockApi()
    render(<FilesPage api={api} />)

    await screen.findByText('banner.png')

    // Initially when root directory tree is shown, NO preview or download URLs are fetched
    expect(api.getBlobPreviewUrl).not.toHaveBeenCalled()
    expect(api.getBlobDownloadUrl).not.toHaveBeenCalled()

    // Select the blob node to activate render
    fireEvent.click(screen.getByText('banner.png'))

    // Active render mounts BlobPreview, which requests preview URL lazily
    await waitFor(() => {
      expect(api.getBlobPreviewUrl).toHaveBeenCalledTimes(1)
      expect(api.getBlobPreviewUrl).toHaveBeenCalledWith(BLOB_IMAGE_NODE.blobId)
    })

    // Download URL is still not fetched until user requests download
    expect(api.getBlobDownloadUrl).not.toHaveBeenCalled()

    const downloadButton = screen.getByRole('button', { name: '下载 banner.png' })
    fireEvent.click(downloadButton)

    await waitFor(() => {
      expect(api.getBlobDownloadUrl).toHaveBeenCalledTimes(1)
      expect(api.getBlobDownloadUrl).toHaveBeenCalledWith(BLOB_IMAGE_NODE.blobId)
    })
  })

  it('supports direct openPath for artifact files without showing artifacts in tree', async () => {
    const artifactFileNode: CloudNodeDTO = {
      id: '00000000-0000-0000-0000-000000000099',
      path: '/.artifacts/tool_xyz/result.txt',
      name: 'result.txt',
      kind: 'TEXT',
      version: '1',
      blobId: null,
      mediaType: 'text/plain',
      sizeBytes: '15',
      sha256: null,
      revision: '1',
      createdAt: null,
      updatedAt: null,
    }

    const api = createMockApi({
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path === '/') {
          return Promise.resolve({
            node: ROOT_DIR_NODE,
            children: [TEXT_FILE_NODE, ARTIFACT_NODE],
            text: null,
            blob: null,
          })
        }
        if (path === '/.artifacts/tool_xyz/result.txt') {
          return Promise.resolve({
            node: artifactFileNode,
            children: null,
            text: {
              revision: '1',
              endsWithNewline: false,
              offset: 1,
              totalLines: 1,
              nextOffset: null,
              lines: [{ lineNumber: 1, content: 'Artifact Output', truncated: false }],
            },
            blob: null,
          })
        }
        return Promise.reject(new Error(`Not found: ${path}`))
      }),
    })

    render(<FilesPage api={api} initialPath="/.artifacts/tool_xyz/result.txt" />)

    // Direct openPath displays the artifact text content in editor
    const textarea = await screen.findByTestId('text-editor-textarea')
    expect(textarea).toHaveValue('Artifact Output')

    // Tree still hides .artifacts
    expect(screen.queryByText('.artifacts')).not.toBeInTheDocument()
  })

  it('triggers create directory modal and submits request', async () => {
    const user = userEvent.setup()
    const api = createMockApi()
    render(<FilesPage api={api} />)

    await screen.findByText('notes.md')

    const newDirBtn = screen.getByRole('button', { name: '新建目录' })
    await user.click(newDirBtn)

    const dialog = screen.getByRole('dialog', { name: '新建目录' })
    expect(dialog).toBeInTheDocument()

    const input = screen.getByLabelText('目录名称')
    await user.type(input, 'my-folder')

    const submitBtn = screen.getByRole('button', { name: '创建' })
    await user.click(submitBtn)

    expect(api.createDirectory).toHaveBeenCalledWith({ path: '/my-folder' })
  })

  it('triggers delete confirm dialog and submits request with expectedVersion', async () => {
    const user = userEvent.setup()
    const api = createMockApi()
    render(<FilesPage api={api} />)

    await screen.findByText('notes.md')

    // Open context menu for notes.md
    const moreBtn = screen.getByRole('button', { name: '操作 notes.md' })
    await user.click(moreBtn)

    const deleteMenuItem = screen.getByRole('menuitem', { name: '删除' })
    await user.click(deleteMenuItem)

    const alertDialog = screen.getByRole('alertdialog', { name: '确认删除 notes.md' })
    expect(alertDialog).toBeInTheDocument()

    const confirmBtn = screen.getByRole('button', { name: '确认删除' })
    await user.click(confirmBtn)

    expect(api.deleteNode).toHaveBeenCalledWith({
      path: '/notes.md',
      expectedVersion: '1',
    })
  })

  it('handles conflict retry/overwrite by fetching latest revision and saving draft', async () => {
    let saveCount = 0
    const conflictError = new ApiError(
      'Revision conflict',
      409,
      'CLOUD_REVISION_CONFLICT',
      { reason: 'CLOUD_REVISION_CONFLICT', detail: 'expected=1 actual=2' },
    )

    const api = createMockApi({
      saveText: vi.fn().mockImplementation(() => {
        saveCount += 1
        if (saveCount === 1) {
          return Promise.reject(conflictError)
        }
        return Promise.resolve({
          ...TEXT_FILE_NODE,
          version: '3',
          revision: '3',
        })
      }),
      getFileSnapshot: vi.fn().mockImplementation((path: string) => {
        if (path === '/') {
          return Promise.resolve({
            node: ROOT_DIR_NODE,
            children: [TEXT_FILE_NODE],
            text: null,
            blob: null,
          })
        }
        if (path === '/notes.md') {
          // Latest server revision is 2
          return Promise.resolve({
            node: { ...TEXT_FILE_NODE, version: '2' },
            children: null,
            text: {
              revision: '2',
              endsWithNewline: true,
              offset: 1,
              totalLines: 1,
              nextOffset: null,
              lines: [{ lineNumber: 1, content: '# Other change', truncated: false }],
            },
            blob: null,
          })
        }
        return Promise.reject(new Error(`Not found: ${path}`))
      }),
    })

    render(<FilesPage api={api} />)

    const notesItem = await screen.findByText('notes.md')
    fireEvent.click(notesItem)

    const textarea = await screen.findByTestId('text-editor-textarea')
    fireEvent.change(textarea, { target: { value: '# My Conflicted Draft' } })

    // First save attempt fails with 409
    const saveBtn = screen.getByRole('button', { name: '保存文件' })
    fireEvent.click(saveBtn)

    const retryBtn = await screen.findByText('使用当前草稿重试保存')
    fireEvent.click(retryBtn)

    // Second save attempt succeeds with updated revision 2 and the preserved draft
    await waitFor(() => {
      expect(api.saveText).toHaveBeenCalledWith({
        path: '/notes.md',
        content: '# My Conflicted Draft',
        expectedRevision: '2',
      })
    })
  })

  it('triggers move node modal and submits request', async () => {
    const user = userEvent.setup()
    const api = createMockApi()
    render(<FilesPage api={api} />)

    await screen.findByText('notes.md')

    const moreBtn = screen.getByRole('button', { name: '操作 notes.md' })
    await user.click(moreBtn)

    const moveMenuItem = screen.getByRole('menuitem', { name: '重命名 / 移动' })
    await user.click(moveMenuItem)

    const dialog = screen.getByRole('dialog', { name: '重命名 / 移动: notes.md' })
    expect(dialog).toBeInTheDocument()

    const destInput = screen.getByLabelText('目标新路径')
    await user.clear(destInput)
    await user.type(destInput, '/docs/renamed.md')

    const confirmBtn = screen.getByRole('button', { name: '确定' })
    await user.click(confirmBtn)

    expect(api.moveNode).toHaveBeenCalledWith({
      sourcePath: '/notes.md',
      destinationPath: '/docs/renamed.md',
      expectedVersion: '1',
    })
  })
})
