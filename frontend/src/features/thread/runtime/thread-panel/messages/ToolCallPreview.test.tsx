import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolCallPreview } from './ToolCallPreview'
import { setLocale } from '@/shared/i18n'

describe('ToolCallPreview 参数流开始', () => {
  // 参数尚未到达时不显示伪造正文；显式空文件由 hasContent 区分。
  it('write 尚无路径或 content 时不显示空文件标识', () => {
    const { container } = render(
      <ToolCallPreview
        streaming
        variant="transcript"
        preview={{ kind: 'write', path: '', content: '', hasContent: false, lines: [] }}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  // edit 的首个参数片段可能只有 path，不能把它当作审查 diff。
  it('edit 尚无条目输入时不显示审查内容', () => {
    const { container } = render(
      <ToolCallPreview
        streaming
        variant="transcript"
        preview={{
          kind: 'edit',
          mode: 'input',
          path: '/workspace/a.txt',
          entries: [{ oldText: '', newText: '', replaceAll: false }],
        }}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })
})

describe('ToolCallPreview 多条目流式输入', () => {
  it('单条目不带条目标题，只保留原文本/新文本标签', () => {
    setLocale('en-US')
    const { container } = render(
      <ToolCallPreview
        streaming
        variant="transcript"
        preview={{
          kind: 'edit',
          mode: 'input',
          path: '/workspace/a.txt',
          entries: [{ oldText: 'one', newText: 'ONE', replaceAll: false }],
        }}
      />,
    )

    expect(screen.queryByText('Change 1 of 1')).not.toBeInTheDocument()
    expect(screen.queryByText('replace all')).not.toBeInTheDocument()
    expect(container.querySelectorAll('.thread-tool-preview-input-block')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-preview-body.is-input-old')?.textContent)
      .toBe('one')
    expect(container.querySelector('.thread-tool-preview-body.is-input-new')?.textContent)
      .toBe('ONE')
  })

  it('多条目按序渲染 Change i of n，并给 replace_all 条目加徽标', () => {
    setLocale('en-US')
    const { container } = render(
      <ToolCallPreview
        streaming
        variant="transcript"
        preview={{
          kind: 'edit',
          mode: 'input',
          path: '/workspace/a.txt',
          entries: [
            { oldText: 'one', newText: 'ONE', replaceAll: true },
            { oldText: 'two', newText: 'TWO', replaceAll: false },
          ],
        }}
      />,
    )

    expect(screen.getByText('Change 1 of 2')).toBeInTheDocument()
    expect(screen.getByText('Change 2 of 2')).toBeInTheDocument()
    const blocks = container.querySelectorAll('.thread-tool-preview-input-block')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]?.querySelector('.thread-tool-preview-badge')?.textContent).toBe('replace all')
    expect(blocks[1]?.querySelector('.thread-tool-preview-badge')).toBeNull()
    expect(blocks[0]?.querySelector('.is-input-old')?.textContent).toBe('one')
    expect(blocks[0]?.querySelector('.is-input-new')?.textContent).toBe('ONE')
    expect(blocks[1]?.querySelector('.is-input-old')?.textContent).toBe('two')
    expect(blocks[1]?.querySelector('.is-input-new')?.textContent).toBe('TWO')
    // 输入不是文件变更：绝不产生 diff 行样式。
    expect(container.querySelector('.is-added, .is-removed, .is-hunk-header')).toBeNull()
  })

  it('单条 replace_all 也显示徽标以便核对影响范围', () => {
    setLocale('en-US')
    render(
      <ToolCallPreview
        streaming
        variant="transcript"
        preview={{
          kind: 'edit',
          mode: 'input',
          path: '/workspace/a.txt',
          entries: [{ oldText: 'one', newText: 'ONE', replaceAll: true }],
        }}
      />,
    )

    expect(screen.getByText('replace all')).toBeInTheDocument()
    expect(screen.queryByText(/Change \d+ of \d+/)).not.toBeInTheDocument()
  })
})
