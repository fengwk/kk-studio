import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  submittedUploadIds,
  unreferencedUploads,
  useComposerSubmissionSettle,
} from '@/features/ai/runtime/thread-panel/useComposerSubmissionSettle'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import type { AttachmentUpload } from '@/features/ai/composer'

function upload(localId: string, overrides: Partial<AttachmentUpload> = {}): AttachmentUpload {
  return {
    localId,
    uploadId: null,
    filename: `${localId}.txt`,
    mediaType: 'text/plain',
    sizeBytes: 1,
    sha256: null,
    status: 'ready',
    progress: 1,
    error: null,
    previewUrl: null,
    detached: false,
    ...overrides,
  }
}

function renderSettle() {
  const markDetached = vi.fn()
  const releaseUpload = vi.fn()
  const rendered = renderHook(
    ({ parts, pending, uploads }: {
      parts: ComposerPart[]
      pending: boolean
      uploads: AttachmentUpload[]
    }) => useComposerSubmissionSettle({
      parts,
      pending,
      uploads,
      markDetached,
      releaseUpload,
    }),
    {
      initialProps: {
        parts: [] as ComposerPart[],
        pending: false,
        uploads: [] as AttachmentUpload[],
      },
    },
  )
  return { ...rendered, markDetached, releaseUpload }
}

describe('submission settle helpers', () => {
  it('maps only the submitted-referenced uploads to their localIds', () => {
    const uploads = [
      upload('local-1', { uploadId: 'up-1' }),
      upload('local-2'),
      upload('local-3', { uploadId: 'up-3' }),
    ]
    const submitted = [
      createTextPart('keep'),
      createAttachmentPart('local-1', 'a.txt'),
      // 服务端句柄同样命中（提交 payload 恢复比对前的本地快照也可按句柄匹配）。
      createAttachmentPart('up-3', 'c.txt'),
    ]
    expect(submittedUploadIds(submitted, uploads)).toEqual(new Set(['local-1', 'local-3']))
  })

  it('releases only unreferenced non-detached uploads', () => {
    const uploads = [
      upload('referenced', { uploadId: 'up-1' }),
      upload('unreferenced'),
      upload('detached-unreferenced', { detached: true }),
      upload('detached-referenced', { uploadId: 'up-2', detached: true }),
    ]
    const parts = [
      createAttachmentPart('referenced', 'a.txt'),
      createAttachmentPart('up-2', 'b.txt'),
    ]
    expect(unreferencedUploads(uploads, parts).map((item) => item.localId)).toEqual([
      'unreferenced',
    ])
  })
})

describe('useComposerSubmissionSettle', () => {
  it('detaches submitted uploads while waiting for the send result', () => {
    const { result, rerender, markDetached, releaseUpload } = renderSettle()
    const draft = [createTextPart('hello'), createAttachmentPart('local-1', 'a.txt')]
    const uploads = [upload('local-1', { uploadId: 'up-1' })]

    act(() => result.current.commit(draft))
    rerender({ parts: [], pending: false, uploads })

    expect(markDetached).toHaveBeenCalledWith(new Set(['local-1']), true)
    expect(releaseUpload).not.toHaveBeenCalled()
  })

  it('keeps uploads detached while the draft is edited instead of restored', () => {
    const { result, rerender, markDetached, releaseUpload } = renderSettle()
    const draft = [createTextPart('hello'), createAttachmentPart('local-1', 'a.txt')]
    const uploads = [upload('local-1', { uploadId: 'up-1' })]

    act(() => result.current.commit(draft))
    rerender({ parts: [], pending: false, uploads })
    rerender({ parts: [createTextPart('edited')], pending: false, uploads })

    expect(markDetached).toHaveBeenLastCalledWith(new Set(['local-1']), true)
    expect(releaseUpload).not.toHaveBeenCalled()
  })

  it('re-mounts submitted uploads when a failed send restores the local draft', () => {
    const { result, rerender, markDetached, releaseUpload } = renderSettle()
    const draft = [createTextPart('keep'), createAttachmentPart('local-1', 'a.txt')]
    const uploads = [upload('local-1', { uploadId: 'up-1' })]

    act(() => result.current.commit(draft))
    rerender({ parts: [], pending: false, uploads })
    expect(markDetached).toHaveBeenLastCalledWith(new Set(['local-1']), true)

    // 失败恢复：parts 回到提交快照 => 上传条目重新挂载（绝不 DELETE）。
    rerender({ parts: draft, pending: false, uploads })
    expect(markDetached).toHaveBeenLastCalledWith(new Set(['local-1']), false)
    expect(releaseUpload).not.toHaveBeenCalled()
  })

  it('releases detached uploads when the pending submission settles successfully', () => {
    const { result, rerender, markDetached, releaseUpload } = renderSettle()
    const draft = [createTextPart('hello'), createAttachmentPart('local-1', 'a.txt')]
    const detachedUploads = [upload('local-1', { uploadId: 'up-1', detached: true })]

    act(() => result.current.commit(draft))
    rerender({ parts: [], pending: true, uploads: detachedUploads })
    rerender({ parts: [], pending: false, uploads: detachedUploads })

    // 结果确定且草稿未恢复 => 发送成功：释放全部挂起条目并结束提交周期。
    expect(releaseUpload).toHaveBeenCalledWith('local-1')
    expect(markDetached).toHaveBeenLastCalledWith(new Set(['local-1']), true)
  })

  it('releases leftover detached uploads when a new message is committed', () => {
    const { result, rerender, releaseUpload } = renderSettle()
    const firstDraft = [createAttachmentPart('local-1', 'a.txt')]
    const leftover = [upload('local-1', { uploadId: 'up-1', detached: true })]

    act(() => result.current.commit(firstDraft))
    rerender({ parts: [], pending: false, uploads: leftover })
    // 新一轮提交：上一轮已 settle 的 detached 残留在此释放。
    act(() => result.current.commit([createTextPart('second')]))

    expect(releaseUpload).toHaveBeenCalledWith('local-1')
  })

  it('releases only unreferenced non-detached uploads outside a submission cycle', () => {
    const { rerender, markDetached, releaseUpload } = renderSettle()
    const uploads = [
      upload('keep-referenced', { uploadId: 'up-1' }),
      upload('remove-me'),
      upload('keep-detached', { detached: true }),
    ]
    rerender({
      parts: [createAttachmentPart('keep-referenced', 'a.txt')],
      pending: false,
      uploads,
    })

    expect(releaseUpload).toHaveBeenCalledTimes(1)
    expect(releaseUpload).toHaveBeenCalledWith('remove-me')
    expect(markDetached).not.toHaveBeenCalled()
  })
})
