import { render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { HistoryTreeRowView } from '@/features/thread/HistoryTreeRow'
import type { HistoryTreeRow } from '@/features/thread/history-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/** 预览中命中词位于深处（第 40 个 UTF-16 单元），单行省略会把它挤出可见区。 */
const DEEP_HIT_PREVIEW = '## 结论 服务停机时先停止接收新请求，再等待在途请求完成，最后释放连接池并记录停机耗时'
/** 命中就在预览开头。 */
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
  // 视觉裁切不能丢失预览正文或读屏文本。
  it('keeps the full preview and accessible label for a deep hit', () => {
    const { entry, preview } = renderRow(DEEP_HIT_PREVIEW, ['停机耗时'])

    expect(preview.textContent).toBe(DEEP_HIT_PREVIEW)
    expect(preview.querySelector('.search-hit-prefix')?.textContent).toBe(DEEP_HIT_PREVIEW.slice(0, -4))
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
