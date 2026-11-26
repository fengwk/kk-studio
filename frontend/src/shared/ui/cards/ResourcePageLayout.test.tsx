import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ResourcePageLayout } from './ResourcePageLayout'
import { ApiError } from '@/shared/api/client'
import { setLocale } from '@/shared/i18n'

afterEach(() => {
  act(() => setLocale('zh-CN'))
})

function renderFrame(
  overrides: Partial<Parameters<typeof ResourcePageLayout>[0]> = {},
) {
  return render(
    <ResourcePageLayout
      search=""
      onSearchChange={() => {}}
      busy={false}
      error={null}
      mutationError={null}
      content={<div data-testid="resource-list">resources</div>}
      {...overrides}
    />,
  )
}

describe('ResourcePageLayout', () => {
  it('renders the resource content when the primary read succeeds', () => {
    renderFrame()

    expect(screen.getByTestId('resource-list')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // 传输层失败只有稳定 code，没有可展示文本：必须按分类给出本地化文案，而不是浏览器的英文原文。
  it('renders the localized category text instead of the raw transport error', () => {
    renderFrame({ error: new ApiError('Failed to fetch', undefined, 'network_error') })

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('网络异常，请稍后重试')
    expect(alert).not.toHaveTextContent('Failed to fetch')
    expect(screen.queryByTestId('resource-list')).not.toBeInTheDocument()
  })

  it('offers a retry action for the page-level failure', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    renderFrame({ error: new ApiError('boom', 500), onRetry })

    await user.click(screen.getByRole('button', { name: '重试' }))

    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
