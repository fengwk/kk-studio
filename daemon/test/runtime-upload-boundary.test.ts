import { describe, expect, it, vi } from 'vitest'

import {
  ResourceUploadError,
  type ResourceUploader,
} from '../src/contracts.js'
import {
  boundaryUpload,
  describeUploadFailure,
} from '../src/runtime-upload-boundary.js'
import { isOutboundCleanupFailure } from '../src/network.js'

const content = {
  type: 'binary' as const,
  bytes: Uint8Array.of(1),
  mediaType: 'application/octet-stream',
}

function uploaderWhich(behavior: () => Promise<never>): ResourceUploader {
  return { upload: vi.fn(behavior) }
}

describe('resource upload boundary contract', () => {
  it('keeps a contract error with cleanupConfirmed=true as-is', async () => {
    const failure = new ResourceUploadError('put', 'storage-rejected', true)
    const uploader = uploaderWhich(() => Promise.reject(failure))
    const error = await boundaryUpload(uploader, 'inv-1', content, new AbortController().signal).then(
      () => undefined,
      (reason: unknown) => reason,
    )
    expect(error).toBe(failure)
  })

  it('downgrades a contract error without cleanup confirmation to exit-unknown', async () => {
    // uploader 声称失败但未确认释放：调用方不能据此 ACK 退出，stage/category 保留。
    const failure = new ResourceUploadError('put', 'storage-rejected', false)
    const uploader = uploaderWhich(() => Promise.reject(failure))
    const error = await boundaryUpload(uploader, 'inv-1', content, new AbortController().signal).then(
      () => undefined,
      (reason: unknown) => reason,
    )
    expect(error).toBeInstanceOf(ResourceUploadError)
    const typed = error as ResourceUploadError
    expect(typed.stage).toBe('put')
    expect(typed.category).toBe('storage-rejected')
    expect(typed.cleanupConfirmed).toBe(false)
    expect(typed.cause).toBe(failure)
  })

  it('treats an off-contract rejection as a conservative boundary violation with exit unknown', async () => {
    // 未按契约实现的 uploader：不能自动认定释放成功；错误类型与诊断都必须保留。
    const stderr = vi.spyOn(process.stderr, 'write').mockImplementation(() => true)
    try {
      const uploader = uploaderWhich(() => Promise.reject(new Error('mysterious failure')))
      const error = await boundaryUpload(uploader, 'inv-2', content, new AbortController().signal).then(
        () => undefined,
        (reason: unknown) => reason,
      )
      expect(error).toBeInstanceOf(ResourceUploadError)
      const typed = error as ResourceUploadError
      expect(typed.stage).toBe('internal')
      expect(typed.category).toBe('boundary-violation')
      expect(typed.cleanupConfirmed).toBe(false)
      // 边界违约诊断只含 invocationId 与错误类型，不回显 message 正文。
      const diagnostics = stderr.mock.calls.map((call) => String(call[0])).join('')
      expect(diagnostics).toContain('inv-2')
      expect(diagnostics).toContain('Error')
      expect(diagnostics).not.toContain('mysterious failure')
    } finally {
      stderr.mockRestore()
    }
  })

  it('resolves when the underlying uploader succeeds', async () => {
    const uploaded = {
      type: 'resource' as const,
      uploadId: '88888888-8888-4888-8888-888888888888',
      mediaType: 'application/octet-stream',
      name: null,
      size: 1,
      sha256: 'a'.repeat(64),
    }
    const uploader: ResourceUploader = { upload: vi.fn(async () => uploaded) }
    await expect(
      boundaryUpload(uploader, 'inv-3', content, new AbortController().signal),
    ).resolves.toBe(uploaded)
  })

  it('describes failures with stage/category/cleanup facts only', () => {
    const confirmed = new ResourceUploadError('request', 'ticket-failed', true)
    expect(describeUploadFailure(confirmed)).toBe(
      'stage=request category=ticket-failed cleanupConfirmed=true',
    )
    const unknown = new Error('storage rejected with https://signed-url-secret')
    const line = describeUploadFailure(unknown)
    expect(line).toContain('cleanupConfirmed=true')
    // 安全诊断绝不回显 cause 文案（可能携带预签名 URL）。
    expect(line).not.toContain('signed-url-secret')
  })

  it('bridges a network cleanup failure into exit-unknown through the boundary', async () => {
    // 网络层确认的清理失败必须让退出事实保持未知，且按类型可判定。
    const cleanup = new Error('dispatcher close failed')
    const uploader = uploaderWhich(() => Promise.reject(cleanup))
    const error = await boundaryUpload(uploader, 'inv-4', content, new AbortController().signal).then(
      () => undefined,
      (reason: unknown) => reason,
    )
    expect(error).toBeInstanceOf(ResourceUploadError)
    const typed = error as ResourceUploadError
    expect(typed.cleanupConfirmed).toBe(false)
    expect(typed.cause).toBe(cleanup)
    // 聚合后仍可按类型判定：cleanup 事实不能被 ResourceUploadError 包装丢失。
    const aggregate = new AggregateError([typed], 'upload and cleanup both failed')
    expect(isOutboundCleanupFailure(aggregate)).toBe(false)
  })
})
