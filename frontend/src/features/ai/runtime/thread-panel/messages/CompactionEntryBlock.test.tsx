import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EntryMessageBlock } from '@/features/ai/runtime/thread-panel/messages/EntryMessageBlock'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/ai/runtime/transcript-reading'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import {
  FULL_COMPACTION_SUMMARY,
  LONG_COMPACTION_SUMMARY,
  UNSAFE_COMPACTION_SUMMARY,
} from '@/test-support/resources/compaction-summary'

function compactionMessage(text: string, id = 'entry-comp1'): EntryEventDialogueMessage {
  return {
    id: `entry:${id}`,
    role: 'entry',
    kind: 'compaction',
    title: '上下文已压缩',
    text,
    subjectEntryId: id,
    createdAt: null,
    status: 'done',
  }
}

function renderCard(text: string, id?: string) {
  return render(<EntryMessageBlock message={compactionMessage(text, id)} />)
}

describe('CompactionEntryBlock', () => {
  it('starts collapsed with a rightmost toggle and no summary body', () => {
    // 测试意图：完整成功压缩默认折叠，第一行最右提供展开控制。
    const { container } = renderCard(FULL_COMPACTION_SUMMARY)

    const card = container.querySelector('[data-entry-kind="compaction"]')
    expect(card).toHaveClass('thread-compaction')
    expect(screen.getByText('上下文已压缩')).toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: '展开压缩摘要' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // 收起时没有正文滚动区，也没有 ChevronDown。
    expect(container.querySelector('.thread-compaction-body')).toBeNull()
    expect(container.querySelector('.lucide-chevron-right')).toBeInTheDocument()
    expect(container.querySelector('.lucide-chevron-down')).toBeNull()
  })

  it('expands to the full summary inside one bounded scroll region', () => {
    // 测试意图：展开显示唯一有界正文区，整份摘要不做首 N 行截断。
    const { container } = renderCard(LONG_COMPACTION_SUMMARY)

    fireEvent.click(screen.getByRole('button', { name: '展开压缩摘要' }))

    const toggle = screen.getByRole('button', { name: '收起压缩摘要' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelector('.lucide-chevron-down')).toBeInTheDocument()

    const bodies = container.querySelectorAll('.thread-compaction-body')
    expect(bodies).toHaveLength(1)
    const body = bodies[0] as HTMLElement
    expect(body).toHaveClass('thread-system-message-body')
    // 唯一滚动区可滚动（jsdom 不计算 CSS 高度，浏览器回归验证真实 max-height）。
    body.scrollTop = 120
    expect(body.scrollTop).toBe(120)
    // 完整正文都在 DOM 中（40 段 + 保留标签），没有截断。
    expect(body.textContent).toContain('第 40 段')
    expect(body.textContent).toContain('<read-files>')
  })

  it('keeps the expanded state when the same durable entry is re-projected', () => {
    // 测试意图：刷新投影以摘要 Entry 的稳定 id 复用组件实例，展开状态不重置。
    const { rerender } = render(<EntryMessageBlock message={compactionMessage('refresh stable')} />)
    fireEvent.click(screen.getByRole('button', { name: '展开压缩摘要' }))
    expect(screen.getByRole('button', { name: '收起压缩摘要' })).toHaveAttribute('aria-expanded', 'true')

    // 新对象、同一摘要身份（MessageList 以 message.id 作为 React key）。
    rerender(<EntryMessageBlock message={compactionMessage('refresh stable')} />)

    expect(screen.getByRole('button', { name: '收起压缩摘要' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('refresh stable')).toBeInTheDocument()
  })

  it('announces reading on toggles and inner scrolling without resuming outer follow', () => {
    // 展开和内部回看都只通知暂停；正文可用键盘滚动。
    const { container } = renderCard(LONG_COMPACTION_SUMMARY)
    const reading = vi.fn()
    container.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, reading)

    fireEvent.click(screen.getByRole('button', { name: '展开压缩摘要' }))
    const body = container.querySelector('.thread-compaction-body') as HTMLElement
    expect(body).toHaveAttribute('tabindex', '0')
    expect(body).toHaveAttribute('aria-label', '上下文已压缩')
    fireEvent.scroll(body, { target: { scrollTop: 120 } })
    fireEvent.click(screen.getByRole('button', { name: '收起压缩摘要' }))

    expect(reading.mock.calls.map(([event]) => (event as CustomEvent).detail)).toEqual([
      { source: 'compaction-toggle' },
      { source: 'compaction-body' },
      { source: 'compaction-toggle' },
    ])
  })

  it('renders normal Markdown while keeping reserved XML tags as text', () => {
    // 测试意图：标题/列表/代码块按 Markdown 渲染；<read-files>/<modified-files>
    // 保留为普通文本并逐行保留换行，不做专用解析或折叠。
    const { container } = renderCard(FULL_COMPACTION_SUMMARY)
    fireEvent.click(screen.getByRole('button', { name: '展开压缩摘要' }))

    expect(container.querySelector('.md-root h1')?.textContent).toBe('会话压缩摘要')
    expect(container.querySelector('.md-root h2')?.textContent).toBe('关键决策')
    expect(container.querySelectorAll('.md-root li')).toHaveLength(2)
    expect(container.querySelector('.md-code-block')?.textContent).toContain('TURN_PREFIX')

    const root = container.querySelector('.thread-compaction-body .md-root') as HTMLElement
    const rawSpans = root.querySelectorAll('.md-raw-text')
    expect(rawSpans).toHaveLength(2)
    expect(rawSpans[0].textContent).toBe(
      '<read-files>\n'
      + 'src/features/ai/runtime/thread-timeline-builder.ts\n'
      + 'src/features/ai/runtime/thread-timeline/entry-event-projection.ts\n'
      + '</read-files>',
    )
    expect(root.textContent).toContain('<modified-files>')
    // 保留标签不能被解析成元素或统计折叠。
    expect(container.querySelector('read-files')).toBeNull()
    expect(container.querySelector('modified-files')).toBeNull()
  })

  it('renders raw HTML as inert text without executing it', () => {
    // 测试意图：摘要中的原始 HTML 不得生成可执行元素，也不产生图片请求。
    const { container } = renderCard(UNSAFE_COMPACTION_SUMMARY)
    fireEvent.click(screen.getByRole('button', { name: '展开压缩摘要' }))

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<script>')
  })
})
