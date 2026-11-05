import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyTextToClipboard } from '@/shared/lib/clipboard'

const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
const execCommandDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand')

afterEach(() => {
  restoreProperty(navigator, 'clipboard', clipboardDescriptor)
  restoreProperty(document, 'execCommand', execCommandDescriptor)
})

describe('copyTextToClipboard', () => {
  // 测试意图：安全上下文优先走异步 Clipboard API，成功时不再触碰 DOM 兜底。
  it('copies through the Clipboard API without touching the fallback', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const execCommand = vi.fn().mockReturnValue(true)
    setClipboard({ writeText })
    setExecCommand(execCommand)

    await expect(copyTextToClipboard('copied text')).resolves.toBe(true)

    expect(writeText).toHaveBeenCalledWith('copied text')
    expect(execCommand).not.toHaveBeenCalled()
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  // 测试意图：权限被拒时回退到临时 textarea + execCommand，并且无论结果如何都清理节点。
  it('falls back to a temporary textarea when the Clipboard API rejects', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    const execCommand = vi.fn().mockReturnValue(true)
    setExecCommand(execCommand)

    await expect(copyTextToClipboard('fallback text')).resolves.toBe(true)

    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  // 测试意图：非安全上下文没有 Clipboard API 时同样走兜底。
  it('uses the fallback when the Clipboard API is unavailable', async () => {
    setClipboard(undefined)
    setExecCommand(vi.fn().mockReturnValue(true))

    await expect(copyTextToClipboard('no api')).resolves.toBe(true)
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  // 测试意图：调用方卸载后不应再插入 textarea 抢焦点，因此跳过兜底并报告失败。
  it('skips the fallback when the caller is no longer active', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    const execCommand = vi.fn().mockReturnValue(true)
    setExecCommand(execCommand)
    const shouldFallback = vi.fn().mockReturnValue(false)

    await expect(copyTextToClipboard('unmounted', { shouldFallback })).resolves.toBe(false)

    expect(shouldFallback).toHaveBeenCalledTimes(1)
    expect(execCommand).not.toHaveBeenCalled()
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  // 测试意图：浏览器明确报告兜底失败或抛错时返回 false，且不残留临时节点。
  it('reports failure when execCommand returns false or throws', async () => {
    setClipboard(undefined)
    setExecCommand(vi.fn().mockReturnValue(false))
    await expect(copyTextToClipboard('rejected')).resolves.toBe(false)
    expect(document.body.querySelector('textarea')).toBeNull()

    setExecCommand(vi.fn(() => {
      throw new Error('unsupported')
    }))
    await expect(copyTextToClipboard('throwing')).resolves.toBe(false)
    expect(document.body.querySelector('textarea')).toBeNull()
  })
})

function setClipboard(value: { writeText: (text: string) => Promise<void> } | undefined) {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value,
  })
}

function setExecCommand(value: (commandId: string) => boolean) {
  Object.defineProperty(document, 'execCommand', {
    configurable: true,
    value,
  })
}

function restoreProperty(
  target: object,
  property: string,
  descriptor: PropertyDescriptor | undefined,
) {
  if (descriptor) {
    Object.defineProperty(target, property, descriptor)
  } else {
    delete (target as Record<string, unknown>)[property]
  }
}
