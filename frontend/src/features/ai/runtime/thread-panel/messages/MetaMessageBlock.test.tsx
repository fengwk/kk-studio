import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MetaMessageBlock } from '@/features/ai/runtime/thread-panel/messages/MetaMessageBlock'
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
})
