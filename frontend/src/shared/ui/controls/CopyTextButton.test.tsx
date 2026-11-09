import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CopyTextButton } from '@/shared/ui/controls/CopyTextButton'
import { copyTextToClipboard } from '@/shared/lib/clipboard'

vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn() }))

const props = {
  source: '/workspace/a',
  label: '复制目录路径',
  copiedLabel: '目录路径已复制',
  failedLabel: '无法复制目录路径',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}

const status = () => screen.getByRole('status')

beforeEach(() => {
  vi.mocked(copyTextToClipboard).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('CopyTextButton', () => {
  it('copies the exact source and shows the checkmark in place for 1.5 seconds', async () => {
    vi.useFakeTimers()
    vi.mocked(copyTextToClipboard).mockResolvedValue(true)
    render(<CopyTextButton {...props} />)
    const button = screen.getByRole('button', { name: '复制目录路径' })

    // 按钮内只有一个固定图标槽、没有可见文字；状态区在按钮之外且不占位。
    expect(button.querySelectorAll('.copy-text-button-icon')).toHaveLength(1)
    expect(button.textContent).toBe('')
    expect(status()).toHaveClass('sr-only')
    expect(button.contains(status())).toBe(false)

    fireEvent.click(button)
    await act(async () => {})

    expect(copyTextToClipboard).toHaveBeenCalledWith('/workspace/a')
    expect(button.querySelector('.lucide-check')).not.toBeNull()
    expect(button).toHaveAttribute('title', '目录路径已复制')
    expect(button).toHaveAttribute('aria-label', '复制目录路径')
    expect(status()).toHaveTextContent('目录路径已复制')

    // 成功反馈停留 1.5 秒后回到空闲图标与动作名称。
    act(() => {
      vi.advanceTimersByTime(1499)
    })
    expect(button.querySelector('.lucide-check')).not.toBeNull()
    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(button.querySelector('.lucide-check')).toBeNull()
    expect(button.querySelector('.lucide-copy')).not.toBeNull()
    expect(button).toHaveAttribute('title', '复制目录路径')
    expect(status()).toBeEmptyDOMElement()
  })

  it('keeps the failure icon and the danger tone in place for 3 seconds', async () => {
    vi.useFakeTimers()
    vi.mocked(copyTextToClipboard).mockResolvedValue(false)
    render(<CopyTextButton {...props} />)
    const button = screen.getByRole('button', { name: '复制目录路径' })

    fireEvent.click(button)
    await act(async () => {})

    expect(button.querySelector('.lucide-circle-alert')).not.toBeNull()
    expect(button).toHaveClass('is-copy-failed')
    expect(button).toHaveAttribute('title', '无法复制目录路径')
    expect(status()).toHaveTextContent('无法复制目录路径')

    act(() => {
      vi.advanceTimersByTime(2999)
    })
    expect(button).toHaveClass('is-copy-failed')
    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(button).not.toHaveClass('is-copy-failed')
    expect(button).toHaveAttribute('title', '复制目录路径')
    expect(status()).toBeEmptyDOMElement()
  })

  it('drops a stale clipboard result after the copy target changes', async () => {
    const user = userEvent.setup()
    const first = deferred<boolean>()
    const second = deferred<boolean>()
    vi.mocked(copyTextToClipboard)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const { rerender } = render(<CopyTextButton {...props} />)
    const button = screen.getByRole('button', { name: '复制目录路径' })

    await user.click(button)
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledTimes(1))

    rerender(<CopyTextButton {...props} source="/workspace/b" />)
    await act(async () => {
      first.resolve(true)
    })

    // 旧目标的成功结果不能落到新目录的按钮上。
    expect(button.querySelector('.lucide-check')).toBeNull()
    expect(button).toHaveAttribute('title', '复制目录路径')
    expect(status()).toBeEmptyDOMElement()

    await user.click(button)
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledTimes(2))
    expect(copyTextToClipboard).toHaveBeenLastCalledWith('/workspace/b')
    await act(async () => {
      second.resolve(true)
    })
    expect(button.querySelector('.lucide-check')).not.toBeNull()
    expect(status()).toHaveTextContent('目录路径已复制')
  })

  it('never lets an earlier in-flight result overwrite the latest click', async () => {
    const user = userEvent.setup()
    const first = deferred<boolean>()
    const second = deferred<boolean>()
    vi.mocked(copyTextToClipboard)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    render(<CopyTextButton {...props} />)
    const button = screen.getByRole('button', { name: '复制目录路径' })

    await user.click(button)
    await user.click(button)
    expect(copyTextToClipboard).toHaveBeenCalledTimes(2)

    await act(async () => {
      second.resolve(false)
    })
    expect(button).toHaveClass('is-copy-failed')

    // 先发起、后完成的成功结果不得覆盖最新一次点击的失败反馈。
    await act(async () => {
      first.resolve(true)
    })
    expect(button).toHaveClass('is-copy-failed')
    expect(button.querySelector('.lucide-circle-alert')).not.toBeNull()
    expect(button.querySelector('.lucide-check')).toBeNull()
  })

  it('clears the pending feedback timer on unmount', async () => {
    vi.useFakeTimers()
    vi.mocked(copyTextToClipboard).mockResolvedValue(true)
    const { unmount } = render(<CopyTextButton {...props} />)

    fireEvent.click(screen.getByRole('button', { name: '复制目录路径' }))
    await act(async () => {})
    expect(vi.getTimerCount()).toBe(1)

    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('CopyTextButton style contract', () => {
  const css = readFileSync(
    resolve(process.cwd(), 'src', 'shared', 'ui', 'controls', 'copy-text-button.css'),
    'utf8',
  )

  it('keeps a fixed icon slot and paints the failure with the danger token', () => {
    const slot = css.match(/\.copy-text-button \.copy-text-button-icon\s*\{[^}]*\}/)?.[0] ?? ''
    const failed = css.match(/\.copy-text-button\.is-copy-failed[^{]*\{[^}]*\}/)?.[0] ?? ''

    // 图标槽固定 14px：Copy/Check/CircleAlert 互换时按钮尺寸不变。
    expect(slot).toContain('width: 14px')
    expect(slot).toContain('height: 14px')
    expect(slot).toContain('flex: 0 0 14px')
    // 失败只换颜色，不引入尺寸或位置变化。
    expect(failed).toContain('color: var(--danger)')
    expect(failed).not.toContain('width')
    expect(failed).not.toContain('position')
  })
})
