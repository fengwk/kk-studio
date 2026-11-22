import { render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { HistoryTreeRowView } from '@/features/thread/HistoryTreeRow'
import type { HistoryTreeRow } from '@/features/thread/history-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/** 预览中命中词位于深处（第 40 个 UTF-16 单元），单行省略会把它挤出可见区。 */
const DEEP_HIT_PREVIEW = '## 结论 服务停机时先停止接收新请求，再等待在途请求完成，最后释放连接池并记录停机耗时'
/** 摘录保留命中词前 6 个字符。 */
const DEEP_HIT_EXCERPT = '…连接池并记录停机耗时'
/** 命中已在开头附近时摘录不改变原预览。 */
const EARLY_HIT_PREVIEW = '停机耗时统计：服务停机时先停止接收新请求'

function row(preview: string): HistoryTreeRow {
  const entry: HarnessSessionEntryDTO = {
    entryId: 'entry-1',
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payload: { message: { role: 'ASSISTANT', contents: [{ type: 'text', text: preview }] } },
    createdAt: 0,
    usageCost: null,
  }
  return {
    entry,
    kind: 'assistant',
    preview,
    text: preview,
    searchText: preview.toLowerCase(),
    lane: 0,
    gutter: [true],
    startsLane: false,
    isFork: false,
    canFork: false,
    onActivePath: true,
    isHead: true,
  }
}

function renderRow(preview: string, tokens: string[]) {
  render(
    <HistoryTreeRowView
      row={row(preview)}
      tokens={tokens}
      dimmed={false}
      selected={false}
      selectedRef={createRef<HTMLButtonElement>()}
      onSelect={vi.fn()}
    />,
  )
  const entry = screen.getByRole('button')
  return {
    entry,
    preview: entry.querySelector('.history-tree-entry-preview')!,
  }
}

describe('HistoryTreeRowView', () => {
  // 测试意图：命中词在预览深处时，窄行也要从命中处附近开始显示并高亮命中；
  // 无障碍标签始终保留完整预览，不能因视觉摘录而丢失读屏文本。
  it('starts the preview near a deep search hit and keeps the full accessible label', () => {
    const { entry, preview } = renderRow(DEEP_HIT_PREVIEW, ['停机耗时'])

    expect(preview.textContent).toBe(DEEP_HIT_EXCERPT)
    expect(preview.querySelector('mark')?.textContent).toBe('停机耗时')
    expect(entry.getAttribute('aria-label')).toContain(DEEP_HIT_PREVIEW)
    expect(entry.getAttribute('aria-label')).toContain('助手')
  })

  // 测试意图：命中已在开头附近时不做摘录，命中词仍高亮。
  it('keeps an early hit in place while highlighting it', () => {
    const { preview } = renderRow(EARLY_HIT_PREVIEW, ['停机耗时'])

    expect(preview.textContent).toBe(EARLY_HIT_PREVIEW)
    expect(preview.querySelectorAll('mark')).toHaveLength(1)
    expect(preview.querySelector('mark')?.textContent).toBe('停机耗时')
  })

  // 测试意图：没有搜索词时展示完整预览，不产生省略前缀或高亮。
  it('shows the full preview when no search token is active', () => {
    const { preview } = renderRow(DEEP_HIT_PREVIEW, [])

    expect(preview.textContent).toBe(DEEP_HIT_PREVIEW)
    expect(preview.querySelector('mark')).toBeNull()
  })
})
