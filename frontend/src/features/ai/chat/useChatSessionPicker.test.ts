import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listAllThreads } from '@/features/ai/chat/useChatSessionPicker'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listThreads: vi.fn(),
  },
}))

describe('useChatSessionPicker pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('rejects a repeated cursor instead of looping forever', async () => {
    vi.mocked(harnessService.listThreads).mockResolvedValue({
      items: [],
      nextCursor: 'same-cursor',
    })

    await expect(listAllThreads('recent')).rejects.toThrow('Thread pagination cursor loop detected')
    expect(harnessService.listThreads).toHaveBeenNthCalledWith(1, {
      sort: 'recent',
      cursor: undefined,
      limit: 100,
    })
    expect(harnessService.listThreads).toHaveBeenNthCalledWith(2, {
      sort: 'recent',
      cursor: 'same-cursor',
      limit: 100,
    })
  })
})
