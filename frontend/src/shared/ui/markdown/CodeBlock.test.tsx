import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CodeBlock,
  CopyButton,
  CopyableShell,
} from '@/shared/ui/markdown/CodeBlock'

const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
const execCommandDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand')

afterEach(() => {
  restoreProperty(navigator, 'clipboard', clipboardDescriptor)
  restoreProperty(document, 'execCommand', execCommandDescriptor)
  vi.useRealTimers()
})

describe('CodeBlock copy behavior', () => {
  it('copies through the Clipboard API, stops bubbling, and resets the success state', async () => {
    // 成功复制只更新按钮自身状态，1500ms 后恢复原标签。
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    const parentClick = vi.fn()
    render(
      <div onClick={parentClick}>
        <CopyButton source="const value = 1" />
      </div>,
    )

    fireEvent.click(screen.getByRole('button', { name: '复制' }))
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('const value = 1')
    expect(parentClick).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(screen.getByRole('button', { name: '复制' })).toBeInTheDocument()
  })

  it('falls back to a temporary textarea when Clipboard API rejects', async () => {
    // Clipboard 权限失败时使用 execCommand，并且无论结果如何都清理临时 textarea。
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    const execCommand = vi.fn().mockReturnValue(true)
    setClipboard({ writeText })
    setExecCommand(execCommand)
    render(<CopyButton source="fallback text" />)

    fireEvent.click(screen.getByRole('button', { name: '复制' }))
    await screen.findByRole('button', { name: '已复制' })

    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  it('keeps the idle state when fallback returns false', async () => {
    // 浏览器明确报告 fallback 失败时不能显示误导性的 copied 状态。
    setClipboard(undefined)
    setExecCommand(vi.fn().mockReturnValue(false))
    render(<CopyButton source="not copied" />)

    fireEvent.click(screen.getByRole('button', { name: '复制' }))
    await flushPromises()

    expect(screen.getByRole('button', { name: '复制' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '已复制' })).not.toBeInTheDocument()
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  it('cleans the fallback textarea when execCommand throws', async () => {
    // execCommand 异常也必须走 finally 清理 DOM，且保持未复制状态。
    setClipboard(undefined)
    setExecCommand(vi.fn(() => {
      throw new Error('unsupported')
    }))
    render(<CopyButton source="throwing fallback" />)

    fireEvent.click(screen.getByRole('button', { name: '复制' }))
    await flushPromises()

    expect(screen.getByRole('button', { name: '复制' })).toBeInTheDocument()
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  it('uses explicit copy labels and renders the code shell contract', () => {
    // 外壳允许调用方覆盖 copy label，CodeBlock 保持 pre/code 结构和 className。
    render(
      <>
        <CopyableShell source="diagram" copyLabel="复制图表">
          <span>diagram body</span>
        </CopyableShell>
        <CodeBlock source="answer" className="language-ts">
          answer
        </CodeBlock>
      </>,
    )

    expect(screen.getByRole('button', { name: '复制图表' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
    expect(screen.getByText('answer')).toHaveClass('language-ts')
    expect(screen.getByText('answer').parentElement?.tagName).toBe('PRE')
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

async function flushPromises() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

function restoreProperty(
  target: object,
  key: PropertyKey,
  descriptor: PropertyDescriptor | undefined,
) {
  if (descriptor) {
    Object.defineProperty(target, key, descriptor)
  } else {
    Reflect.deleteProperty(target, key)
  }
}
