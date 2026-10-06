import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MetaMessageBlock } from '@/features/ai/runtime/thread-panel/messages/MetaMessageBlock'
import { EntryBranchContext } from '@/features/ai/runtime/thread-panel/entry-branch-context'
import type { MetaDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

describe('MetaMessageBlock', () => {
  // 意图：验证 turn_usage 消息在截断或省略时，title 完整保留实际数值摘要和本地化图例两部分，避免信息丢失。
  it('combines full usage text and localized legend into title for kind-turn_usage', () => {
    const usageText = '↑203 · ↓51 · R4.1k · $0.003 · cache 95% · 87 tok/s'
    const message: MetaDialogueMessage = {
      id: 'meta-msg-1',
      role: 'meta',
      kind: 'turn_usage',
      text: usageText,
      createdAt: 1000,
    }

    render(<MetaMessageBlock message={message} />)

    const block = screen.getByText(usageText)
    expect(block).toBeInTheDocument()
    expect(block).toHaveClass('thread-meta-text')

    const expectedLegend = translate('ai.runtime.usage.metaTooltip')
    expect(block).toHaveAttribute('title', `${usageText}\n${expectedLegend}`)
  })

  // 意图：绑定真实 TURN_END 的回合 footer 即使没有 usage 文本也必须渲染结束信息与分支入口。
  it('renders the turn-end footer with a branch entry even without usage text', () => {
    const message: MetaDialogueMessage = {
      id: 'meta-end-1',
      role: 'meta',
      kind: 'turn_usage',
      text: '',
      endEntryId: 'end-1',
      createdAt: 2000,
    }

    render(
      <EntryBranchContext.Provider value={vi.fn()}>
        <MetaMessageBlock message={message} />
      </EntryBranchContext.Provider>,
    )

    expect(screen.getByTestId('thread-turn-end-branch')).toBeInTheDocument()
    const section = document.querySelector('.thread-block-meta')
    expect(section).toHaveAttribute('data-turn-end', 'end-1')
  })

  // 意图：没有 TURN_END 绑定的普通 usage 摘要不应出现分支入口。
  it('does not render a branch entry without a turn-end binding', () => {
    const message: MetaDialogueMessage = {
      id: 'meta-msg-2',
      role: 'meta',
      kind: 'turn_usage',
      text: '↑1 · ↓1 · $0.1 · cache 0% · 1 tok/s',
      createdAt: 1000,
    }

    render(
      <EntryBranchContext.Provider value={vi.fn()}>
        <MetaMessageBlock message={message} />
      </EntryBranchContext.Provider>,
    )
    expect(screen.queryByTestId('thread-turn-end-branch')).toBeNull()
  })

  // 意图：分支入口必须走外层注入的同一命名/目标流程，并携带真正关闭该回合的 TURN_END entryId。
  it('requests a branch from the turn-end entry through the injected context', () => {
    const request = vi.fn()
    const message: MetaDialogueMessage = {
      id: 'meta-end-2',
      role: 'meta',
      kind: 'turn_usage',
      text: '↑1 · ↓1 · $0.1 · cache 0% · 1 tok/s',
      endEntryId: 'end-42',
      createdAt: 2000,
    }

    render(
      <EntryBranchContext.Provider value={request}>
        <MetaMessageBlock message={message} />
      </EntryBranchContext.Provider>,
    )

    const button = screen.getByTestId('thread-turn-end-branch')
    expect(button).toBeEnabled()
    fireEvent.click(button)
    expect(request).toHaveBeenCalledWith('end-42')
  })

  // 意图：上下文不具备分支能力（value 为 null）时不渲染入口，而不是留下一个禁用按钮。
  it('omits the branch entry entirely when the context has no branching capability', () => {
    const message: MetaDialogueMessage = {
      id: 'meta-end-3',
      role: 'meta',
      kind: 'turn_usage',
      text: '',
      endEntryId: 'end-9',
      createdAt: 2000,
    }

    render(
      <EntryBranchContext.Provider value={null}>
        <MetaMessageBlock message={message} />
      </EntryBranchContext.Provider>,
    )
    expect(screen.queryByTestId('thread-turn-end-branch')).toBeNull()
    // 结束信息本身仍然可见
    const section = document.querySelector('.thread-block-meta')
    expect(section).toHaveAttribute('data-turn-end', 'end-9')
  })
})
