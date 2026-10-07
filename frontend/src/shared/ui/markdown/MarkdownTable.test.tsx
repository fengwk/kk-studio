import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { MarkdownTable } from '@/shared/ui/markdown/MarkdownTable'
import { readMarkdownTableSource } from '@/shared/ui/markdown/markdownTableSource'

const mermaidApi = vi.hoisted(() => ({
  initialize: vi.fn<(config: Record<string, unknown>) => void>(),
  render: vi.fn<(id: string, text: string) => Promise<{ svg: string }>>(),
}))

vi.mock('mermaid', () => ({
  default: mermaidApi,
}))

/** 富文本单元格：转义竖线、加粗、链接、行内代码都必须按原文复制。 */
const RICH_TABLE = [
  '| 名称 | 值 | 备注 |',
  '| :--- | ---: | :---: |',
  '| `a\\|b` | **粗体** | [链接](https://example.com/x) |',
  '| x | y | z |',
].join('\n')

const SECOND_TABLE = ['| two | table |', '| --- | --- |', '| 1 | 2 |'].join('\n')

const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
const execCommandDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand')

beforeEach(() => {
  mermaidApi.render.mockReset()
  mermaidApi.render.mockImplementation(async (_id, text) => ({
    svg: `<svg data-source="${text}"></svg>`,
  }))
})

afterEach(() => {
  restoreProperty(navigator, 'clipboard', clipboardDescriptor)
  restoreProperty(document, 'execCommand', execCommandDescriptor)
  vi.useRealTimers()
})

describe('MarkdownRenderer table copy', () => {
  it('copies each of two tables as its own raw Markdown instead of the whole message', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    render(
      <MarkdownRenderer
        content={['前言', '', RICH_TABLE, '', '中间', '', SECOND_TABLE, '', '结尾'].join('\n')}
      />,
    )

    const buttons = screen.getAllByRole('button', { name: '复制表格' })
    expect(buttons).toHaveLength(2)

    fireEvent.click(buttons[0])
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(RICH_TABLE)

    fireEvent.click(buttons[1])
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(SECOND_TABLE)
  })

  it('keeps table offsets local to their own segment around a mermaid fence', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    const view = render(
      <MarkdownRenderer
        content={[
          RICH_TABLE,
          '',
          '```mermaid',
          'graph TD; A-->B',
          '```',
          '',
          SECOND_TABLE,
        ].join('\n')}
      />,
    )
    await waitFor(() => {
      expect(view.container.querySelector('.md-mermaid')?.innerHTML).toContain('graph TD; A-->B')
    })

    const buttons = screen.getAllByRole('button', { name: '复制表格' })
    expect(buttons).toHaveLength(2)

    fireEvent.click(buttons[0])
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(RICH_TABLE)

    // 第二张表在前一段之后的 segment 里：offset 重置，不能沿用整条消息的位置。
    fireEvent.click(buttons[1])
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(SECOND_TABLE)
    expect(writeText.mock.calls.flat().join('\n')).not.toContain('mermaid')
  })

  it('copies the full current table after a streaming append', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    const partial = ['| a | b |', '| --- | --- |', '| 1 | 2 |'].join('\n')
    const view = render(<MarkdownRenderer content={partial} />)

    fireEvent.click(copyButton(view.container))
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(partial)

    view.rerender(<MarkdownRenderer content={`${partial}\n| 3 | 4 |`} />)
    fireEvent.click(copyButton(view.container))
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(`${partial}\n| 3 | 4 |`)
  })

  it('shows the copied feedback on success and clears it on unmount', async () => {
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    const view = render(<MarkdownRenderer content={RICH_TABLE} />)

    fireEvent.click(screen.getByRole('button', { name: '复制表格' }))
    await flushPromises()
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(screen.getByRole('button', { name: '复制表格' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '复制表格' }))
    await flushPromises()
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('never reports success when the clipboard rejects and the fallback fails', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    setClipboard({ writeText })
    setExecCommand(vi.fn().mockReturnValue(false))
    render(<MarkdownRenderer content={SECOND_TABLE} />)

    fireEvent.click(screen.getByRole('button', { name: '复制表格' }))
    await flushPromises()

    expect(screen.getByRole('button', { name: '复制表格' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '已复制' })).toBeNull()
    expect(document.body.querySelector('textarea')).toBeNull()
  })

  it('keeps table structure, column alignment and a copy button outside the table', () => {
    const view = render(
      <MarkdownRenderer content={['| 左 | 中 | 右 |', '| :--- | :---: | ---: |', '| 1 | 2 | 3 |'].join('\n')} />,
    )
    const shell = view.container.querySelector('.md-table-shell')
    const table = screen.getByRole('table')
    expect(shell?.contains(table)).toBe(true)
    expect(table.querySelector('thead th')).not.toBeNull()
    expect(table.querySelectorAll('thead th')).toHaveLength(3)
    expect(table.querySelectorAll('tbody td')).toHaveLength(3)
    expect(view.container.querySelectorAll('tbody tr')).toHaveLength(1)

    // React 把 GFM 的 align 渲染成 text-align 行内样式，列对齐语义不变。
    const alignments = Array.from(table.querySelectorAll('thead th')).map((cell) =>
      cell.getAttribute('style'),
    )
    expect(alignments).toEqual([
      'text-align: left;',
      'text-align: center;',
      'text-align: right;',
    ])
    expect(Array.from(table.querySelectorAll('thead th')).map((cell) => cell.textContent)).toEqual([
      '左',
      '中',
      '右',
    ])

    // 按钮与 table 同级并排在前面：既不落在单元格里覆盖内容，也先于表格进入焦点顺序。
    const button = screen.getByRole('button', { name: '复制表格' })
    expect(button.parentElement).toBe(shell)
    expect(table.contains(button)).toBe(false)
    expect(shell?.firstElementChild).toBe(button)
  })

  it('renders the table without a copy button when no segment source is available', () => {
    // 位置缺失时宁可不提供复制，也不静默复制空源码。
    render(
      <MarkdownTable>
        <tbody>
          <tr>
            <td>a</td>
          </tr>
        </tbody>
      </MarkdownTable>,
    )
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })
})

describe('readMarkdownTableSource', () => {
  it('slices the exact source segment and rejects positions without offsets', () => {
    const source = `head\n${RICH_TABLE}\ntail`
    const start = source.indexOf(RICH_TABLE)
    expect(
      readMarkdownTableSource(source, {
        start: { offset: start },
        end: { offset: start + RICH_TABLE.length },
      }),
    ).toBe(RICH_TABLE)

    expect(readMarkdownTableSource(null, { start: { offset: 0 }, end: { offset: 4 } })).toBeNull()
    expect(readMarkdownTableSource(source, undefined)).toBeNull()
    expect(readMarkdownTableSource(source, { start: { offset: 5 }, end: { offset: 5 } })).toBeNull()
  })
})

function copyButton(container: HTMLElement): HTMLButtonElement {
  const button = container.querySelector<HTMLButtonElement>('button.md-table-copy')
  if (!button) {
    throw new Error('table copy button not found')
  }
  return button
}

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
