import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SystemMessageBody } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBody'
import { FULL_COMPACTION_SUMMARY, UNSAFE_COMPACTION_SUMMARY } from '@/test-support/resources/compaction-summary'

/**
 * SystemMessageBody 是通知与压缩摘要共用的系统正文：安全 Markdown + 保留 raw 节点换行。
 */
describe('SystemMessageBody', () => {
  it('renders normal Markdown and line-preserved reserved tags', () => {
    const view = render(<SystemMessageBody content={FULL_COMPACTION_SUMMARY} />)
    const body = view.container.querySelector('.thread-system-message-body')
    expect(body).not.toBeNull()
    expect(body?.querySelector('.md-root h1')?.textContent).toBe('会话压缩摘要')
    expect(body?.querySelectorAll('.md-root li')).toHaveLength(2)
    expect(body?.querySelector('.md-code-block')?.textContent).toContain('TURN_PREFIX')

    // <read-files>/<modified-files> 各自是一个安全 span，逐行保留，不解析成元素。
    const rawSpans = body?.querySelectorAll('.md-raw-text') ?? []
    expect(rawSpans).toHaveLength(2)
    expect(rawSpans[0].textContent).toContain(
      '<read-files>\nsrc/features/ai/runtime/thread-timeline-builder.ts',
    )
    expect(body?.querySelector('read-files')).toBeNull()
    expect(body?.querySelector('modified-files')).toBeNull()
  })

  it('renders raw HTML as inert text without executing it', () => {
    const view = render(<SystemMessageBody content={UNSAFE_COMPACTION_SUMMARY} />)
    expect(view.container.querySelector('script')).toBeNull()
    expect(view.container.querySelector('img')).toBeNull()
    expect(view.container.textContent).toContain('<script>')
  })
})
