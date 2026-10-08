import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThreadNavigationContext } from '@/features/ai/runtime/thread-navigation-context'
import { EntryMessageBlock } from '@/features/ai/runtime/thread-panel/messages/EntryMessageBlock'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import {
  SUBAGENT_TASK_PREVIEW_LIMIT,
  subagentTaskPreview,
} from '@/features/ai/runtime/thread-panel/messages/subagent-receipt-preview'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

type ReceiptState = 'completed' | 'error' | 'cancelled'

/**
 * 与运行端同形的回执信封：说明文字里的 <task> 必须写成实体，正文按运行端规则转义。
 */
function receiptEnvelope(options: {
  agent?: string | null
  state: ReceiptState
  threadId?: string
  task?: string | null
  result?: string
  error?: string
  partial?: string
}): string {
  const attributes = [`thread_id="${options.threadId ?? 'child-thread-1'}"`]
  if (options.agent != null) {
    attributes.push(`agent="${options.agent}"`)
  }
  attributes.push(`state="${options.state}"`)
  const lines = [`<subagent_result ${attributes.join(' ')}>`]
  if (options.task != null) {
    lines.push('<task>', escapeText(options.task), '</task>')
  }
  if (options.result != null) {
    lines.push('<result>', escapeText(options.result), '</result>')
  }
  if (options.error != null) {
    lines.push('<error>', escapeText(options.error), '</error>')
  }
  if (options.partial != null) {
    lines.push('<partial_result>', escapeText(options.partial), '</partial_result>')
  }
  lines.push('</subagent_result>')
  return lines.join('\n')
}

/** 复刻运行端转义，保证测试输入与真实信封同形。 */
function escapeText(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

function subagentMessage(
  xml: string,
  sourceThreadId: string | null = 'child-thread-1',
  id = 'entry-subagent',
): EntryEventDialogueMessage {
  return {
    id,
    role: 'entry',
    kind: 'notification',
    title: 'subagent 结果',
    text: xml,
    notification: { kind: 'SUBAGENT_RESULT', sourceThreadId },
    subjectEntryId: id,
    createdAt: null,
    status: 'done',
  }
}

function otherNotificationMessage(
  kind: string,
  text: string,
): EntryEventDialogueMessage {
  return {
    id: `entry-${kind}`,
    role: 'entry',
    kind: 'notification',
    title: kind === 'TASK_BUDGET' ? '任务预算提醒' : '系统通知',
    text,
    notification: { kind, sourceThreadId: 'child-thread-1' },
    subjectEntryId: `entry-${kind}`,
    createdAt: null,
    status: 'done',
  }
}

function renderNotification(
  message: EntryEventDialogueMessage,
  onOpenThread?: (threadId: string) => void,
) {
  return render(
    <MemoryRouter>
      {onOpenThread ? (
        <ThreadNavigationContext.Provider value={onOpenThread}>
          <EntryMessageBlock message={message} />
        </ThreadNavigationContext.Provider>
      ) : (
        <EntryMessageBlock message={message} />
      )}
    </MemoryRouter>,
  )
}

function cardOf(container: HTMLElement): HTMLElement {
  const card = container.querySelector<HTMLElement>('.thread-subagent-receipt')
  expect(card).not.toBeNull()
  return card as HTMLElement
}

function toggleOf(container: HTMLElement): HTMLElement {
  const toggle = container.querySelector<HTMLElement>('.thread-subagent-receipt-toggle')
  expect(toggle).not.toBeNull()
  return toggle as HTMLElement
}

describe('subagentTaskPreview', () => {
  it('flattens whitespace and truncates long tasks without touching the source text', () => {
    // 测试意图：折叠预览只做前端单行截取，不截断原始数据本身。
    expect(subagentTaskPreview(null)).toBeNull()
    expect(subagentTaskPreview('   \n\t  ')).toBeNull()
    expect(subagentTaskPreview('line one\n\nline two')).toBe('line one line two')

    const long = 'word '.repeat(40)
    const preview = subagentTaskPreview(long)
    expect(preview).toBe(`${long.replace(/\s+/gu, ' ').trim().slice(0, SUBAGENT_TASK_PREVIEW_LIMIT)}…`)
    expect(preview!.length).toBeLessThanOrEqual(SUBAGENT_TASK_PREVIEW_LIMIT + 1)

    const exactlyLimited = 'x'.repeat(SUBAGENT_TASK_PREVIEW_LIMIT)
    expect(subagentTaskPreview(exactlyLimited)).toBe(exactlyLimited)
  })
})

describe('SubagentReceipt collapsed summary', () => {
  it('defaults to a collapsed info card showing agent, real terminal state, task preview and source link', () => {
    // 测试意图：回执默认只展示一行摘要；完整 task/result 必须留在折叠区之外，来源是独立链接。
    const task = 'delegate '.repeat(30)
    const { container } = renderNotification(
      subagentMessage(receiptEnvelope({
        agent: 'coder',
        state: 'completed',
        task,
        result: '## Done\n\n- item one',
      })),
    )

    const card = cardOf(container)
    expect(card).toHaveClass('thread-notification')
    expect(card).toHaveAttribute('data-entry-kind', 'notification')
    expect(card).toHaveAttribute('data-notification-kind', 'SUBAGENT_RESULT')
    expect(card).toHaveAttribute('data-subagent-state', 'completed')

    // 一行摘要：agent 名 + 真实终态标签 + 任务短预览，且不叠加通用系统标题。
    expect(screen.getByText('coder')).toBeInTheDocument()
    expect(card.querySelector('.thread-subagent-receipt-state.is-completed')).not.toBeNull()
    expect(screen.getByRole('img', { name: '已返回' })).toHaveAttribute('title', '已返回')
    expect(container.textContent).not.toContain('已返回')
    expect(container.querySelector('.thread-system-message-header')).toBeNull()
    expect(container.textContent).not.toContain('subagent 结果')

    const preview = card.querySelector('.thread-subagent-receipt-preview')
    expect(preview?.textContent).toBe(subagentTaskPreview(task))
    expect(container.textContent).not.toContain(task.slice(SUBAGENT_TASK_PREVIEW_LIMIT + 5))

    // 默认折叠：没有任何回执正文分区。
    expect(container.querySelector('.thread-subagent-receipt-detail')).toBeNull()
    expect(container.textContent).not.toContain('Done')

    const toggle = toggleOf(container)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // 静态检查：没有图标列与左缩进，仍走全覆盖系统卡结构。
    expect(container.querySelector('.thread-entry-row')).toBeNull()
    expect(container.querySelector('.thread-entry-icon')).toBeNull()

    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', '/threads/child-thread-1')
    expect(link.nextElementSibling).toBe(toggle)
    expect(toggle.parentElement?.lastElementChild).toBe(toggle)
  })

  it('falls back to the generic subagent label when the envelope has no agent name', () => {
    const { container } = renderNotification(
      subagentMessage(receiptEnvelope({ state: 'completed', result: 'done' })),
    )

    expect(container.querySelector('.thread-subagent-receipt-agent')?.textContent).toBe('子代理')
  })
})

describe('SubagentReceipt expansion', () => {
  it('expands to the full task, result and partial/error sections with safe Markdown', async () => {
    const user = userEvent.setup()
    const task = `${'delegate '.repeat(30)}FINAL_TASK_MARKER`
    const { container } = renderNotification(
      subagentMessage(receiptEnvelope({
        agent: 'explorer',
        state: 'error',
        task,
        error: 'provider **failed**',
        partial: 'half a report',
      })),
    )

    await user.click(toggleOf(container))
    expect(toggleOf(container)).toHaveAttribute('aria-expanded', 'true')

    const detail = container.querySelector('.thread-subagent-receipt-detail')
    expect(detail).not.toBeNull()
    // 展开后逐字展示完整 task（不再截断）与 Markdown 化结果。
    expect(detail!.textContent).toContain(task)
    expect(detail!.textContent).toContain('任务')
    expect(detail!.textContent).toContain('provider failed')
    expect(detail!.querySelector('.thread-subagent-receipt-error strong')?.textContent)
      .toBe('failed')
    expect(detail!.textContent).toContain('部分结果')
    expect(detail!.textContent).toContain('half a report')

    await user.click(toggleOf(container))
    expect(container.querySelector('.thread-subagent-receipt-detail')).toBeNull()
  })

  it('opens and closes the summary line with the keyboard', async () => {
    // 测试意图：摘要是原生按钮，Enter/Space 都能展开收起，来源链接保持独立可聚焦。
    const user = userEvent.setup()
    const { container } = renderNotification(
      subagentMessage(receiptEnvelope({ agent: 'coder', state: 'completed', result: 'done' })),
    )
    const toggle = toggleOf(container)

    toggle.focus()
    await user.keyboard('{Enter}')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    await user.keyboard(' ')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    expect(screen.getByRole('link')).toHaveAttribute('href', '/threads/child-thread-1')
  })

  it('keeps the expanded state when the same entry is re-rendered', async () => {
    const user = userEvent.setup()
    const message = subagentMessage(
      receiptEnvelope({ agent: 'coder', state: 'completed', result: 'done' }),
    )
    const { container, rerender } = renderNotification(message)
    await user.click(toggleOf(container))

    rerender(
      <MemoryRouter>
        <EntryMessageBlock message={{ ...message }} />
      </MemoryRouter>,
    )

    expect(toggleOf(container)).toHaveAttribute('aria-expanded', 'true')
  })
})

describe('SubagentReceipt terminal states', () => {
  it('labels completed, error and cancelled with their own state, never a fourth partial state', () => {
    const completed = renderNotification(
      subagentMessage(receiptEnvelope({ agent: 'coder', state: 'completed', result: 'done' })),
    )
    expect(cardOf(completed.container)).toHaveAttribute('data-subagent-state', 'completed')
    completed.unmount()

    const failed = renderNotification(
      subagentMessage(receiptEnvelope({
        agent: 'explorer',
        state: 'error',
        error: 'boom',
        partial: 'half',
      })),
    )
    expect(cardOf(failed.container)).toHaveAttribute('data-subagent-state', 'error')
    fireEvent.click(toggleOf(failed.container))
    // partial_result 是内容分区，不是状态：状态仍是 error，不存在 partial 终态。
    expect(failed.container.textContent).toContain('错误')
    expect(failed.container.textContent).toContain('部分结果')
    expect(cardOf(failed.container)).not.toHaveAttribute('data-subagent-state', 'partial')
    failed.unmount()

    const cancelled = renderNotification(
      subagentMessage(receiptEnvelope({ agent: 'coder', state: 'cancelled', error: 'Cancelled by user' })),
    )
    expect(cardOf(cancelled.container)).toHaveAttribute('data-subagent-state', 'cancelled')
    fireEvent.click(toggleOf(cancelled.container))
    expect(cancelled.container.textContent).toContain('已取消')
    expect(cancelled.container.textContent).toContain('Cancelled by user')
  })
})

describe('SubagentReceipt identity and safety', () => {
  it('keeps every receipt of the same source thread as its own entry', () => {
    // 测试意图：同一 subagent 续跑的多条回执按各自 Entry 身份呈现，不按 sourceThreadId 去重。
    const messages: EntryEventDialogueMessage[] = [
      subagentMessage(
        receiptEnvelope({ agent: 'coder', state: 'completed', task: 'first run', result: 'first' }),
        'child-thread-1',
        'entry-a',
      ),
      subagentMessage(
        receiptEnvelope({ agent: 'coder', state: 'completed', task: 'second run', result: 'second' }),
        'child-thread-1',
        'entry-b',
      ),
    ]

    const { container } = render(
      <MemoryRouter>
        <MessageList messages={messages} />
      </MemoryRouter>,
    )

    const cards = container.querySelectorAll('.thread-subagent-receipt')
    expect(cards).toHaveLength(2)
    expect(container.textContent).toContain('first run')
    expect(container.textContent).toContain('second run')

    // 展开只作用于被点击的那条回执。
    fireEvent.click(cards[0].querySelector('.thread-subagent-receipt-toggle') as HTMLElement)
    expect(cards[0].querySelector('.thread-subagent-receipt-detail')).not.toBeNull()
    expect(cards[1].querySelector('.thread-subagent-receipt-detail')).toBeNull()
  })

  it('shows a failure for invalid envelopes instead of pretending they are receipts', () => {
    const { container } = renderNotification(
      subagentMessage('<subagent_result thread_id="child-thread-1" agent="coder" state="completed">'),
    )

    expect(screen.getByText('subagent 回执不是合法的 XML 信封，无法展示。')).toBeInTheDocument()
    expect(container.querySelector('.thread-subagent-receipt-detail')).toBeNull()
    expect(container.querySelector('.thread-subagent-receipt-toggle')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
    expect(container.textContent).not.toContain('subagent_result')
  })

  it('rejects a receipt whose thread does not match the notification source', () => {
    const { container } = renderNotification(
      subagentMessage(
        receiptEnvelope({ agent: 'coder', state: 'completed', result: 'done' }),
        'another-thread',
      ),
    )

    expect(screen.getByText('subagent 回执不是合法的 XML 信封，无法展示。')).toBeInTheDocument()
    expect(container.querySelector('.thread-subagent-receipt-detail')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('keeps decoded markup as inert text and makes no request when expanded', async () => {
    // 测试意图：运行端只把还原后的正文当文本渲染；还原出的标签不得执行，也不触发任何请求。
    const user = userEvent.setup()
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const { container } = renderNotification(
      subagentMessage([
        '<subagent_result thread_id="child-thread-1" agent="coder" state="completed">',
        '<task>read &lt;read-files&gt;</task>',
        '<result>safe **text**\n\n&lt;script&gt;alert(1)&lt;/script&gt;\n\n&lt;img src="x" onerror="alert(1)" /&gt;</result>',
        '</subagent_result>',
      ].join('\n')),
    )

    await user.click(toggleOf(container))
    const detail = container.querySelector('.thread-subagent-receipt-detail') as HTMLElement
    expect(detail.textContent).toContain('read <read-files>')
    expect(detail.textContent).toContain('<script>alert(1)</script>')
    expect(detail.querySelector('script')).toBeNull()
    expect(detail.querySelector('img')).toBeNull()
    expect(detail.querySelector('.md-root strong')?.textContent).toBe('text')
    expect(fetchSpy).not.toHaveBeenCalled()
    fetchSpy.mockRestore()
  })

  it('navigates in-pane when the source link is clicked', () => {
    const onOpenThread = vi.fn()
    renderNotification(
      subagentMessage(receiptEnvelope({ agent: 'coder', state: 'completed', result: 'done' })),
      onOpenThread,
    )

    fireEvent.click(screen.getByRole('link'))
    expect(onOpenThread).toHaveBeenCalledWith('child-thread-1')
  })
})

describe('SubagentReceipt scope', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('leaves TASK_BUDGET and unknown notifications on the generic system card', () => {
    const budget = renderNotification(otherNotificationMessage('TASK_BUDGET', 'budget **exhausted**'))
    expect(budget.container.querySelector('.thread-subagent-receipt')).toBeNull()
    expect(budget.container.querySelector('.thread-system-message-header')).not.toBeNull()
    expect(screen.getByText('exhausted')).toBeInTheDocument()
    budget.unmount()

    const unknown = renderNotification(otherNotificationMessage('SOMETHING_ELSE', 'opaque system fact'))
    expect(unknown.container.querySelector('.thread-subagent-receipt')).toBeNull()
    expect(screen.getByText('系统通知')).toBeInTheDocument()
    expect(screen.getByText('opaque system fact')).toBeInTheDocument()
    expect(screen.queryByRole('link')).toBeNull()
  })
})
