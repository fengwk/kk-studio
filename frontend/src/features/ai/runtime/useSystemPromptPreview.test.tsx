import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSystemPromptPreview } from '@/features/ai/runtime/useSystemPromptPreview'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getSystemPromptPreview: vi.fn(),
  },
}))

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

describe('useSystemPromptPreview', () => {
  beforeEach(() => {
    vi.mocked(harnessService.getSystemPromptPreview).mockReset()
  })

  it('refreshes on both turn start and turn end', async () => {
    vi.mocked(harnessService.getSystemPromptPreview)
      .mockResolvedValueOnce({ text: '<current_environment>\n- date: 2026-08-17\n</current_environment>' })
      .mockResolvedValueOnce({ text: '<current_environment>\n- name: local-dev\n</current_environment>' })
      .mockResolvedValueOnce({ text: '<current_environment>\n- name: local-dev\n- system: wsl\n</current_environment>' })
    const { result, rerender } = renderHook(
      ({ working }) => useSystemPromptPreview('thread-1', true, working),
      { wrapper: createWrapper(), initialProps: { working: false } },
    )

    await waitFor(() => expect(result.current).toContain('- date:'))
    expect(harnessService.getSystemPromptPreview).toHaveBeenCalledTimes(1)

    // SET_AGENT/SET_MODEL 与 USER_MESSAGE 同批生效后，working=true 代表最新 branch 已可现算。
    rerender({ working: true })
    await waitFor(() => expect(result.current).toContain('- name: local-dev'))
    expect(harnessService.getSystemPromptPreview).toHaveBeenCalledTimes(2)

    rerender({ working: false })
    await waitFor(() => expect(result.current).toContain('- system: wsl'))
    expect(harnessService.getSystemPromptPreview).toHaveBeenCalledTimes(3)
  })
})
