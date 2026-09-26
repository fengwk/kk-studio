import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import { UserMessageBlock } from '@/features/ai/runtime/thread-panel/messages/UserMessageBlock'
import { ResourceBlobUrlContext } from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'

const text = (value: string) => ({ type: 'text', text: value })
const image = (name: string) => ({ type: 'resource', blobId: name, name, mediaType: 'image/png' })

function message(contents: Record<string, unknown>[]) {
  const timeline = buildThreadTimeline([{
    entryId: 'user-1',
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({ message: { role: 'USER', contents } }),
    createTime: '2026-09-26T00:00:00',
  }], [], [])
  const projected = timeline.messages[0]!
  if (projected.role !== 'user') {
    throw new Error('Expected a user message')
  }
  return projected
}

describe('UserMessageBlock ordered contents', () => {
  // 从持久消息投影到实际 DOM，确保不是只在组件 mock 中保留顺序。
  it.each([
    [text('这是啥'), image('a.png'), text('老鼠么')],
    [image('a.png'), text('中间'), image('b.png')],
    [text('前'), image('a.png'), image('a.png'), text('后')],
    [image('a.png')],
    [text('第一行\n第二行'), text('另一段')],
  ])('preserves content order for %j', async (...contents) => {
    const projected = message(contents)
    const { container, unmount } = render(
      <ResourceBlobUrlContext.Provider value={async (blobId) => ({
        original: `https://media.test/${blobId}`,
        preview: `https://media.test/preview/${blobId}`,
        mediaType: 'image/png',
        sizeBytes: 128,
      })}>
        <UserMessageBlock message={projected} />
      </ResourceBlobUrlContext.Provider>,
    )
    const imageCount = contents.filter((content) => content.type === 'resource').length
    if (imageCount > 0) {
      expect(await screen.findAllByRole('img')).toHaveLength(imageCount)
    }
    const section = container.querySelector('.thread-block-user')!
    const actual = [...section.children].map((child) => {
      const img = child.querySelector('img')
      return img ? { type: 'resource', name: img.alt } : { type: 'text', text: child.textContent }
    })
    expect(actual).toEqual(contents.map((content) =>
      content.type === 'resource'
        ? { type: 'resource', name: content.name }
        : { type: 'text', text: content.text },
    ))
    // 图片依然使用缩略图，点击打开原图，不因顺序修复改变资源解析行为。
    if (imageCount > 0) {
      const preview = screen.getAllByRole('img')[0]!
      expect(preview.getAttribute('src')).toContain('/preview/')
      fireEvent.click(preview.closest('button')!)
      const dialog = screen.getByRole('dialog')
      expect(within(dialog).getByRole('img').getAttribute('src')).not.toContain('/preview/')
    }
    unmount()
  })

  // 资源无法解析时，错误占位也必须保留在原位置。
  it('keeps an unavailable resource between text blocks', async () => {
    const { container } = render(
      <UserMessageBlock message={message([text('前'), image('gone.png'), text('后')])} />,
    )
    await screen.findByText('资源不可用')
    const blocks = container.querySelector('.thread-block-user')!.children
    expect(blocks[0]).toHaveTextContent('前')
    expect(blocks[1]).toHaveTextContent('[gone.png]')
    expect(blocks[2]).toHaveTextContent('后')
  })

  // 无资源的纯文本展示模型无需构造内容数组，空文本不制造空块。
  it('renders plain text and omits empty text blocks', () => {
    const base = message([text('纯文本')])
    const { container, rerender } = render(<UserMessageBlock message={{ ...base, contents: undefined }} />)
    expect(screen.getByText('纯文本')).toBeInTheDocument()
    rerender(<UserMessageBlock message={{ ...base, contents: [text('')] }} />)
    expect(container.querySelector('.thread-block-user')).toBeEmptyDOMElement()
  })
})
