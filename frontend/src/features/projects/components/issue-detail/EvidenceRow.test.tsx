import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { IssueEvidenceDTO } from '@/shared/api/generated/contracts'
import type { StorageService } from '@/shared/api/storage-service'
import { setLocale } from '@/shared/i18n'
import { EvidenceRow } from './EvidenceRow'

const evidence: IssueEvidenceDTO = {
  issueId: 'issue-00000000-0000-0000-0000-000000000001',
  blobId: 'blob-1',
  uri: '',
  name: 'oauth2-design.png',
  actorAgentName: null,
  runId: null,
  createdAt: Date.UTC(2026, 8, 20, 10, 15),
}

function renderRow(storageService: StorageService) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <EvidenceRow evidence={evidence} storageService={storageService} />
    </QueryClientProvider>,
  )
}

function createStorageService(): StorageService {
  return {
    getBlobDownloadUrl: vi.fn(),
    getBlobPreviewUrl: vi.fn(),
  } as unknown as StorageService
}

describe('EvidenceRow 下载入口', () => {
  let openSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    window.localStorage.clear()
    // 清空存储后必须显式恢复语言，否则 i18n 会回落到默认 en-US 导致中文断言失效。
    setLocale('zh-CN')
    vi.clearAllMocks()
    openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
  })

  afterEach(() => {
    openSpy.mockRestore()
    setLocale('zh-CN')
  })

  it('reports a failed download or preview inline and retries it in place', async () => {
    // 下载地址与预览地址都失败时不能在空 catch 里结束：行内说明失败，重试成功后正常打开。
    const storageService = createStorageService()
    vi.mocked(storageService.getBlobDownloadUrl).mockRejectedValue(new Error('download unavailable'))
    vi.mocked(storageService.getBlobPreviewUrl).mockRejectedValue(new Error('preview unavailable'))
    renderRow(storageService)

    fireEvent.click(await screen.findByRole('link', { name: /下载\/查看/ }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('证据打开失败')
    expect(openSpy).not.toHaveBeenCalled()

    // 存储恢复后重试就是同一个动作：成功后收起提示，并真的打开签好名的地址。
    vi.mocked(storageService.getBlobPreviewUrl).mockResolvedValue({
      method: 'GET',
      url: 'https://storage.example.com/preview/blob-1',
      headers: {},
      expiresAt: Date.UTC(2026, 9, 27),
      sizeBytes: null,
      mediaType: null,
    })
    fireEvent.click(screen.getByRole('button', { name: '重试' }))

    await waitFor(() => {
      expect(openSpy).toHaveBeenCalledWith('https://storage.example.com/preview/blob-1', '_blank', 'noreferrer')
    })
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })

  it('opens a signed url directly without reporting a failure', async () => {
    // 正常路径不产生任何失败提示，避免把可用的证据入口变成告警行。
    const storageService = createStorageService()
    vi.mocked(storageService.getBlobDownloadUrl).mockResolvedValue({
      method: 'GET',
      url: 'https://storage.example.com/download/blob-1',
      headers: {},
      expiresAt: Date.UTC(2026, 9, 27),
      sizeBytes: '1024',
      mediaType: 'image/png',
    })
    renderRow(storageService)

    const link = await screen.findByRole('link', { name: /下载\/查看/ })
    expect(link).toHaveAttribute('href', 'https://storage.example.com/download/blob-1')
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
