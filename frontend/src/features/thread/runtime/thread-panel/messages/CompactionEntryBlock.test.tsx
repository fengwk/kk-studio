import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EntryMessageBlock } from '@/features/thread/runtime/thread-panel/messages/EntryMessageBlock'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/transcript-reading'
import type { EntryEventDialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
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
    createdAt: 1785232800000,
    status: 'done',
  }
}

function renderCard(text: string, id?: string) {
  return render(<EntryMessageBlock message={compactionMessage(text, id)} />)
}

function previewOf(container: HTMLElement): HTMLElement {
  const preview = container.querySelector<HTMLElement>('.thread-disclosure-preview')
  expect(preview).not.toBeNull()
  return preview as HTMLElement
}

describe('CompactionEntryBlock', () => {
  it('defaults to zero lines with a rightmost disclosure toggle', () => {
    // 测试意图：压缩摘要默认只有标题；收起态不渲染正文，也不使用卡片内滚动。
    const { container } = renderCard(FULL_COMPACTION_SUMMARY)

    const card = container.querySelector('[data-entry-kind="compaction"]')
    expect(card).toHaveClass('thread-compaction', 'thread-disclosure', 'is-neutral')
    expect(screen.getByText('上下文已压缩')).toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: '展开详情' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(container.textContent).not.toContain('会话压缩摘要')
    expect(container.querySelector('.lucide-chevron-right')).toBeInTheDocument()
    expect(container.querySelector('.lucide-chevron-down')).toBeNull()
  })

  it('expands to the full summary without an internal scroll region', () => {
    // 测试意图：展开显示整份摘要（不做首 N 行截断），正文区自身不是滚动容器。
    const { container } = renderCard(LONG_COMPACTION_SUMMARY)

    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))

    const toggle = screen.getByRole('button', { name: '收起详情' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelector('.lucide-chevron-down')).toBeInTheDocument()

    const preview = previewOf(container)
    const bodies = preview.querySelectorAll('.thread-system-message-body')
    expect(bodies).toHaveLength(1)
    // 完整正文都在 DOM 中（40 段 + 保留标签），没有截断。
    expect(preview.textContent).toContain('第 40 段')
    expect(preview.textContent).toContain('<read-files>')
    // 完整正文在唯一的 preview 中，容器不创建额外的 body 区域或内部滚动区。
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
  })

  it('keeps the expanded state when the same durable entry is re-projected', () => {
    // 测试意图：刷新投影以摘要 Entry 的稳定 id 复用组件实例，展开状态不重置。
    const { rerender } = render(<EntryMessageBlock message={compactionMessage('refresh stable')} />)
    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    expect(screen.getByRole('button', { name: '收起详情' })).toHaveAttribute('aria-expanded', 'true')

    // 新对象、同一摘要身份（MessageList 以 message.id 作为 React key）。
    rerender(<EntryMessageBlock message={compactionMessage('refresh stable')} />)

    expect(screen.getByRole('button', { name: '收起详情' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('refresh stable')).toBeInTheDocument()
  })

  it('announces reading when the summary is expanded or collapsed', () => {
    // 展开与收起都是阅读意图，统一容器只通知外层暂停跟随。
    const { container } = renderCard(LONG_COMPACTION_SUMMARY)
    const reading = vi.fn()
    container.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, reading)

    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    fireEvent.click(screen.getByRole('button', { name: '收起详情' }))

    expect(reading.mock.calls.map(([event]) => (event as CustomEvent).detail)).toEqual([
      { source: 'disclosure-toggle' },
      { source: 'disclosure-toggle' },
    ])
  })

  it('renders normal Markdown while keeping reserved XML tags as text', () => {
    // 测试意图：标题/列表/代码块按 Markdown 渲染；<read-files>/<modified-files>
    // 保留为普通文本并逐行保留换行，不做专用解析或折叠。
    const { container } = renderCard(FULL_COMPACTION_SUMMARY)
    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))

    const preview = previewOf(container)
    expect(preview.querySelector('.md-root h1')?.textContent).toBe('会话压缩摘要')
    expect(preview.querySelector('.md-root h2')?.textContent).toBe('关键决策')
    expect(preview.querySelectorAll('.md-root li')).toHaveLength(2)
    expect(preview.querySelector('.md-code-block')?.textContent).toContain('TURN_PREFIX')

    const root = preview.querySelector('.md-root') as HTMLElement
    const rawSpans = root.querySelectorAll('.md-raw-text')
    expect(rawSpans).toHaveLength(2)
    expect(rawSpans[0].textContent).toBe(
      '<read-files>\n'
      + 'src/features/thread/runtime/thread-timeline-builder.ts\n'
      + 'src/features/thread/runtime/thread-timeline/entry-event-projection.ts\n'
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
    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<script>')
  })
})
