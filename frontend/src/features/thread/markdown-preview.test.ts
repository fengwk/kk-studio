import { describe, expect, it } from 'vitest'
import { translate } from '@/shared/i18n'
import { markdownPreview } from './markdown-preview'

describe('markdownPreview', () => {
  // Keep visible words in order for both rendering and search, including folded input.
  it.each([
    ['## 结论\n### 步骤\n- **加粗**\n> 调用`code`\n1. 完成', '结论 步骤 加粗 调用code 完成'],
    ['## 结论 **加粗**后调用`code` ### 步骤 [手册](https://example.test)', '结论 加粗后调用code 步骤 手册'],
    ['__bold__ and *italic* and _italic_', 'bold and italic and italic'],
    ['![预览](image.png) [手册](https://example.test/a(b))', '预览 手册'],
    ['> > - 引用清单\r\n+ 第二项\r\n* 第三项', '引用清单 第二项 第三项'],
    ['```ts\n# literal\nconst snake_case = a * b\n```', '# literal const snake_case = a * b'],
    ['~~~txt\n**literal**\n~~~', '**literal**'],
    ['``` a * b ``` and ``a`b``', 'a * b and a`b'],
    ['`**literal**` `## example`', '**literal** ## example'],
    ['a * b, a > b, snake_case, a__b__c, C#, issue #42', 'a * b, a > b, snake_case, a__b__c, C#, issue #42'],
    ['unpaired * and ` or _', 'unpaired * and ` or _'],
    ['## Report\n| **Name** | Value |\n| :--- | ---: |\n| [Docs](https://example.test) | `ok` |', 'Report Name Value Docs ok'],
    ['Name | Value\n--- | ---\none | two\n\na | b is prose', 'Name Value one two a | b is prose'],
    ['# ***Report***\n[**Read docs**](guide.md)', 'Report Read docs'],
    ['## Report\n```mermaid\n**graph** | node', 'Report ```mermaid **graph** | node'],
    ['~~~js\n# literal\n`code`', '~~~js # literal `code`'],
    [null, ''],
    [' \n ', ''],
  ])('converts %j to %j', (source, expected) => {
    expect(markdownPreview(source)).toBe(expected)
  })

  // The renderer's closed Mermaid fence takes precedence over ordinary code fences.
  it('substitutes the localized diagram label and preserves neighboring code', () => {
    const source = 'Before\n```MERMAID\ngraph TD; A-->B\n```\n```js\nconst flags = a | b\n```\n> After'
    expect(markdownPreview(source)).toBe(`Before ${translate('shared.mermaidDiagram')} const flags = a | b After`)
  })
})
