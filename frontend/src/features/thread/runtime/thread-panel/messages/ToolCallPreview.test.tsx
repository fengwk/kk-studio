import { render, screen, within } from '@testing-library/react'
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
  // 预览窗口（只留新文本、旧文本进详情）由宿主决定；这里只验证按收到的材料渲染，且不冒充 diff。
  it('单条目不带条目标题，新文本作为独立正文块渲染', () => {
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
    expect(container.querySelector('.thread-tool-preview-body.is-input-new')?.textContent)
      .toBe('ONE')
    expect(container.querySelector('.is-added, .is-removed, .is-hunk-header')).toBeNull()
  })

  it('流式输入明确标注为拟修改内容与新旧文本，而不是 diff', () => {
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

    expect(screen.getByText('拟修改内容')).toBeInTheDocument()
    expect(screen.getByText('原文本：')).toBeInTheDocument()
    expect(screen.getByText('新文本：')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    expect(container.querySelector('.is-added, .is-removed, .is-hunk-header')).toBeNull()
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
    expect(blocks[0]?.querySelector('.is-input-new')?.textContent).toBe('ONE')
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

describe('ToolCallPreview 审批审查正文', () => {
  const review = { path: '/workspace/a.txt', diff: '-old\n+new', sha256: null }

  // 审批默认给完整冻结正文；后端冻结的 diff 只通过原生 summary 按需查看。
  it('write 审批默认展示完整正文，审查 Diff 收在原生 summary 内', () => {
    const content = 'first\nsecond\nthird'
    const { container } = render(
      <ToolCallPreview
        streaming={false}
        variant="approval"
        preview={{
          kind: 'write',
          path: '/workspace/a.txt',
          workdir: '/workspace/root',
          content,
          hasContent: true,
          lines: content.split('\n'),
          review,
        }}
      />,
    )

    expect(container.querySelectorAll('.thread-tool-call-body')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-preview-target-path')?.textContent)
      .toBe('/workspace/a.txt')

    // 正文不被 diff 替换，也不按 10 行窗口截断。
    const contentView = container.querySelector('.thread-tool-preview-body.is-write')
    expect(contentView).toBeInTheDocument()
    expect(contentView?.textContent).toBe(content)
    expect(contentView).toHaveClass('is-bounded')

    const summary = screen.getByText('审查 Diff').closest('summary')
    expect(summary).not.toBeNull()
    const details = summary?.closest('details') as HTMLElement | null
    expect(details).not.toBeNull()
    expect(container.querySelector('details')).toBe(details)
    expect(within(details as HTMLElement).getByText('-old')).toHaveClass('is-removed')
    expect(within(details as HTMLElement).getByText('+new')).toHaveClass('is-added')
  })

  // 空文件在审批里也要明确标注，不能让审批人当作无内容跳过。
  it('write 审批的空文件保留空文件标识', () => {
    const { container } = render(
      <ToolCallPreview
        streaming={false}
        variant="approval"
        preview={{
          kind: 'write',
          path: '/workspace/empty.txt',
          content: '',
          hasContent: true,
          lines: [],
        }}
      />,
    )

    expect(container.querySelector('.thread-tool-empty-content')).toHaveTextContent('空文件')
  })

  // 正文分支仍需把 workdir 放在路径行，供审批时确认写入目录。
  it('write 正文分支的路径行带 workdir', () => {
    const { container } = render(
      <ToolCallPreview
        streaming={false}
        variant="approval"
        preview={{
          kind: 'write',
          path: '/workspace/a.txt',
          workdir: '/workspace/root',
          content: 'hello',
          hasContent: true,
          lines: ['hello'],
        }}
      />,
    )

    expect(container.querySelectorAll('.thread-tool-call-body')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-preview-target-workdir')?.textContent)
      .toBe('· /workspace/root')
  })

  // edit 审批行为不变：只有后端冻结的多段 diff，没有正文也没有审查开关。
  it('edit 审批只展示冻结 diff，不出现正文或审查 Diff 开关', () => {
    const { container } = render(
      <ToolCallPreview
        streaming={false}
        variant="approval"
        preview={{
          kind: 'edit',
          mode: 'review',
          path: '/workspace/a.txt',
          diff: '-old\n+new',
          sha256: null,
          lines: ['-old', '+new'],
        }}
      />,
    )

    expect(container.querySelector('.thread-tool-preview-body.is-edit')?.textContent)
      .toBe('-old\n+new')
    expect(container.querySelector('.thread-tool-preview-body.is-write')).toBeNull()
    expect(container.querySelector('details')).toBeNull()
    expect(screen.queryByText('审查 Diff')).not.toBeInTheDocument()
  })
})
