import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { EntryMessageBlock } from '@/features/thread/runtime/thread-panel/messages/EntryMessageBlock'
import {
  CompactionActionsContext,
  type CompactionActions,
} from '@/features/thread/runtime/thread-panel/compaction-actions-context'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/thread-timeline/transcript-reading'
import type {
  CompactionCardFact,
  EntryEventDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import {
  FULL_COMPACTION_SUMMARY,
  LONG_COMPACTION_SUMMARY,
  UNSAFE_COMPACTION_SUMMARY,
} from '@/test-support/resources/compaction-summary'

function compactionFact(overrides: Partial<CompactionCardFact> = {}): CompactionCardFact {
  return { state: 'compacted', childThreadId: null, cutEntryId: null, errorCode: null, ...overrides }
}

function compactionMessage(
  text: string,
  id = 'entry-comp1',
  fact: CompactionCardFact = compactionFact(),
): EntryEventDialogueMessage {
  return {
    id: `entry:${id}`,
    role: 'entry',
    kind: 'compaction',
    title: '上下文已压缩',
    text,
    subjectEntryId: id,
    createdAt: 1785232800000,
    status: 'done',
    compaction: fact,
  }
}

function compactionCard(fact: CompactionCardFact, text = ''): EntryEventDialogueMessage {
  return {
    ...compactionMessage(text, 'turn-start-1', fact),
    title: 'ignored',
    createdAt: 1767225600000,
  }
}

function renderCard(text: string, id?: string) {
  return render(<EntryMessageBlock message={compactionMessage(text, id)} />)
}

/** 压缩卡的动作渲染在真实宿主上下文里：时间线只读卡片 + 根控制面的压缩能力。 */
function renderWithActions(
  message: EntryEventDialogueMessage,
  actions: CompactionActions | null,
) {
  return render(
    <MemoryRouter>
      <CompactionActionsContext.Provider value={actions}>
        <EntryMessageBlock message={message} />
      </CompactionActionsContext.Provider>
    </MemoryRouter>,
  )
}

function readyActions(overrides: Partial<CompactionActions> = {}): CompactionActions {
  return {
    compact: vi.fn(),
    compactPending: false,
    availability: { status: 'ready', availability: { available: true, disabledReason: null } },
    ...overrides,
  }
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
    expect(preview.querySelector('.md-code-block')?.textContent).toContain('continueModel')

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
  it('opens the execution view from any card that carries a branch thread', () => {
    const { container } = renderWithActions(compactionCard({
      state: 'compacted',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: null,
    }, 'summary'), null)

    const link = screen.getByRole('link', { name: '查看执行' })
    expect(link).toHaveAttribute('href', '/threads/child-1?afterEntryId=cut-1')
    expect(container.querySelector('[data-compaction-state="compacted"]')).not.toBeNull()
  })

  it('shows the failure reason and retries through the shared manual compaction action', () => {
    const compact = vi.fn()
    renderWithActions(compactionCard({
      state: 'failed',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: 'COMPACTION_OUTPUT_TRUNCATED',
    }), readyActions({ compact }))

    expect(screen.getByText('上下文压缩失败')).toBeInTheDocument()
    expect(screen.getByText('摘要因输出上限被截断。')).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: '重新压缩' })
    expect(retry).toBeEnabled()
    fireEvent.click(retry)
    expect(compact).toHaveBeenCalledTimes(1)
  })

  it('offers retry for stopped compactions and reports the unavailable reason', () => {
    renderWithActions(compactionCard({
      state: 'stopped',
      childThreadId: null,
      cutEntryId: null,
      errorCode: null,
    }), readyActions({
      availability: {
        status: 'ready',
        availability: { available: false, disabledReason: 'THREAD_BUSY' },
      },
    }))

    expect(screen.getByText('上下文压缩已停止')).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: '重新压缩' })
    expect(retry).toBeDisabled()
    expect(retry).toHaveAttribute('title', 'Thread 正在执行，请等待当前回合结束')
  })

  it('does not offer the write action in read-only contexts', () => {
    renderWithActions(compactionCard({
      state: 'failed',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: 'COMPACTION_FAILED',
    }), null)

    expect(screen.queryByRole('button', { name: '重新压缩' })).toBeNull()
    expect(screen.getByRole('link', { name: '查看执行' })).toBeInTheDocument()
  })

  it('renders the running card without a summary body or retry', () => {
    const { container } = renderWithActions(compactionCard({
      state: 'running',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: null,
    }), readyActions())

    expect(screen.getByText('正在压缩上下文…')).toBeInTheDocument()
    expect(screen.getByText('正在整理以上内容。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新压缩' })).toBeNull()
    expect(container.querySelector('.thread-disclosure-toggle')).toBeNull()
  })
})
