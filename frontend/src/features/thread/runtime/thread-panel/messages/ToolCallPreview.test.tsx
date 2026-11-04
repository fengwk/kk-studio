import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolCallPreview } from './ToolCallPreview'

describe('ToolCallPreview 参数流开始', () => {
  // 参数尚未到达时不显示伪造正文；显式空文件由 hasContent 区分。
  it('write 尚无路径或 content 时不显示空文件标识', () => {
    const { container } = render(
      <ToolCallPreview
        streaming
        preview={{ kind: 'write', path: '', content: '', hasContent: false, lines: [] }}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  // edit 的首个参数片段可能只有 path，不能把它当作审查 diff。
  it('edit 尚无 old/new 输入时不显示审查内容', () => {
    const { container } = render(
      <ToolCallPreview
        streaming
        preview={{
          kind: 'edit',
          mode: 'input',
          path: '/workspace/a.txt',
          oldText: '',
          newText: '',
          replaceAll: false,
        }}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })
})
