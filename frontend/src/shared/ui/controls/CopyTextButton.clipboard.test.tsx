import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CopyTextButton } from '@/shared/ui/controls/CopyTextButton'

/**
 * 这一组不 mock clipboard helper：真实 `copyTextToClipboard` 在 Clipboard API 失败后会走
 * textarea + `document.execCommand('copy')` 兜底，这里验证旧请求不会触发该兜底。
 */
const props = {
  source: '/workspace/a',
  label: '复制目录路径',
  copiedLabel: '目录路径已复制',
  failedLabel: '无法复制目录路径',
}

const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand')
const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
let execCommand: ReturnType<typeof vi.fn>
/** jsdom 不实现 Clipboard API，这里按真实浏览器形态提供会失败的 writeText。 */
let writeText: ReturnType<typeof vi.fn>

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  writeText = vi.fn(() => Promise.resolve())
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  })
  execCommand = vi.fn(() => false)
  Object.defineProperty(document, 'execCommand', {
    configurable: true,
    value: execCommand,
  })
})

afterEach(() => {
  if (originalClipboard == null) {
    delete (navigator as unknown as Record<string, unknown>).clipboard
  } else {
    Object.defineProperty(navigator, 'clipboard', originalClipboard)
  }
  if (originalExecCommand == null) {
    delete (document as unknown as Record<string, unknown>).execCommand
  } else {
    Object.defineProperty(document, 'execCommand', originalExecCommand)
  }
  vi.restoreAllMocks()
})

describe('CopyTextButton clipboard fallback ownership', () => {
  it.each(['source change', 'unmount'] as const)(
    'never runs the DOM fallback for a rejected copy after %s',
    async (phase) => {
      const pending = deferred<void>()
      writeText.mockReturnValueOnce(pending.promise)
      const view = render(<CopyTextButton {...props} />)

      fireEvent.click(screen.getByRole('button', { name: '复制目录路径' }))
      expect(writeText).toHaveBeenCalledTimes(1)

      if (phase === 'source change') {
        view.rerender(<CopyTextButton {...props} source="/workspace/b" />)
      } else {
        view.unmount()
      }

      await act(async () => {
        pending.reject(new Error('denied'))
        await Promise.resolve()
        await Promise.resolve()
      })

      // 已失去所有权的请求即使失败也只能返回失败，不能插入 textarea 抢焦点。
      expect(execCommand).not.toHaveBeenCalled()
    },
  )

  it('never runs the DOM fallback for a superseded copy but keeps it for the latest click', async () => {
    const first = deferred<void>()
    const second = deferred<void>()
    writeText
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    render(<CopyTextButton {...props} />)
    const button = screen.getByRole('button', { name: '复制目录路径' })

    fireEvent.click(button)
    fireEvent.click(button)
    expect(writeText).toHaveBeenCalledTimes(2)

    await act(async () => {
      first.reject(new Error('denied'))
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(execCommand).not.toHaveBeenCalled()

    // 最新一次点击仍持有所有权，失败后照常走兜底（这里被桩为失败），不会因代次判断被误封。
    await act(async () => {
      second.reject(new Error('denied'))
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(execCommand).toHaveBeenCalledTimes(1)
  })
})
