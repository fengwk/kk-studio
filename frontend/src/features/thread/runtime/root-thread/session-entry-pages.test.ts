import { beforeEach, describe, expect, it, vi } from 'vitest'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'
import { collectSessionEntries } from '@/features/thread/runtime/root-thread/session-entry-pages'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: { listSessionEntries: vi.fn() },
}))

function entry(entryId: string, parentEntryId: string | null = null): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId,
    entryType: parentEntryId == null ? 'ROOT' : 'MESSAGE',
    payloadJson: '{}',
    createTime: 0,
    usageCost: null,
  }
}

describe('collectSessionEntries', () => {
  beforeEach(() => {
    vi.mocked(harnessService.listSessionEntries).mockReset()
  })

  /** 历史树与草稿路径需要完整历史：游标必须逐页推进并按服务端顺序累积。 */
  it('drains every keyset page in order and forwards the abort signal', async () => {
    const signal = new AbortController().signal
    vi.mocked(harnessService.listSessionEntries)
      .mockResolvedValueOnce({ items: [entry('entry-1'), entry('entry-2', 'entry-1')], nextCursor: 'entry-2' })
      .mockResolvedValueOnce({ items: [entry('entry-3', 'entry-2')], nextCursor: null })

    await expect(collectSessionEntries('session-1', signal)).resolves.toEqual([
      entry('entry-1'),
      entry('entry-2', 'entry-1'),
      entry('entry-3', 'entry-2'),
    ])
    expect(harnessService.listSessionEntries).toHaveBeenNthCalledWith(
      1,
      'session-1',
      { cursor: null, signal },
    )
    expect(harnessService.listSessionEntries).toHaveBeenNthCalledWith(
      2,
      'session-1',
      { cursor: 'entry-2', signal },
    )
  })

  /** 末页不再发起空查询，避免无意义的额外请求。 */
  it('stops after a page whose nextCursor is null', async () => {
    vi.mocked(harnessService.listSessionEntries)
      .mockResolvedValueOnce({ items: [entry('entry-1')], nextCursor: null })

    await expect(collectSessionEntries('session-1')).resolves.toEqual([entry('entry-1')])
    expect(harnessService.listSessionEntries).toHaveBeenCalledTimes(1)
  })

  /** 中途失败不得把部分历史当成完整历史交给历史树或草稿路径。 */
  it('rejects when a later page fails instead of returning a partial history', async () => {
    vi.mocked(harnessService.listSessionEntries)
      .mockResolvedValueOnce({ items: [entry('entry-1')], nextCursor: 'entry-1' })
      .mockRejectedValueOnce(new Error('offline'))

    await expect(collectSessionEntries('session-1')).rejects.toThrow('offline')
  })
})
