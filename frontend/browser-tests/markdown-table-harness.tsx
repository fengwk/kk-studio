import { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

/**
 * M1 表格复制的真实浏览器 harness：挂载真实 AssistantMessageBlock（含外层「复制全文」
 * 与共享 MarkdownRenderer），用窄表 + Mermaid 分段 + 宽表覆盖只能在浏览器验证的契约：
 * 按钮与表头/单元格的几何关系、宽表横滚时按钮位置、外层 overflow 是否裁掉按钮、
 * hover/键盘显隐、剪贴板拿到的确实是表格原始 Markdown、流式追加后的分段稳定性。
 */

/** 窄表：富文本单元格（转义竖线/加粗/链接/行内代码），表头就是按钮必须避开的区域。 */
const NARROW_HEADER = '| 名称 | 值 | 备注 |'
const NARROW_ALIGN = '| :--- | ---: | :---: |'
const NARROW_BODY = '| `a\\|b` | **粗体** | [链接](https://example.com/x) |'

/** 追加行模拟流式增长；追加只改变第一段，Mermaid 之后的宽表段应保持已挂载。 */
function appendedRow(index: number): string {
  return `| 追加 ${index + 1} | 值${index + 1} | 备注${index + 1} |`
}

function narrowTableSource(appendedRows = 0): string {
  return [
    NARROW_HEADER,
    NARROW_ALIGN,
    NARROW_BODY,
    ...Array.from({ length: appendedRows }, (_, index) => appendedRow(index)),
  ].join('\n')
}

/** 宽表：12 列长不可断字符串（min-content 不能收缩），真实触发表格自身的横向滚动。 */
const WIDE_TABLE = [
  `| ${Array.from({ length: 12 }, (_, index) => `列${index + 1}`).join(' | ')} |`,
  `| ${
    Array.from({ length: 12 }, (_, index) => (index === 1 ? ':---:' : index === 2 ? '---:' : ':---'))
      .join(' | ')
  } |`,
  `| ${
    Array.from({ length: 12 }, (_, index) => `val${index + 1}-${'x'.repeat(36)}`).join(' | ')
  } |`,
  `| ${
    Array.from({ length: 12 }, (_, index) => `val${index + 100}-${'y'.repeat(36)}`).join(' | ')
  } |`,
].join('\n')

function buildMessage(appendedRows: number): TextDialogueMessage {
  return {
    id: 'entry-table',
    role: 'assistant',
    subjectEntryId: 'entry-table',
    createdAt: '2026-10-06T10:00:00Z',
    status: 'done',
    text: [
      '## 表格复制检查',
      '',
      narrowTableSource(appendedRows),
      '',
      '普通段落。',
      '',
      '```mermaid',
      'graph TD; A-->B',
      '```',
      '',
      WIDE_TABLE,
      '',
      '结尾段落。',
    ].join('\n'),
  }
}

declare global {
  interface Window {
    markdownTableHarness: {
      /** 追加一行窄表数据，模拟流式追加。 */
      appendRow(): void
      /** 当前窄表应复制到的原始 Markdown。 */
      narrowTableSource(appendedRows?: number): string
      /** 宽表应复制到的原始 Markdown。 */
      wideTableSource(): string
    }
  }
}

export function MarkdownTableHarnessApp() {
  const [appendedRows, setAppendedRows] = useState(0)

  useEffect(() => {
    window.markdownTableHarness = {
      appendRow: () => setAppendedRows((count) => count + 1),
      narrowTableSource,
      wideTableSource: () => WIDE_TABLE,
    }
  }, [])

  return (
    <div style={{ width: 760, padding: 24 }}>
      <AssistantMessageBlock message={buildMessage(appendedRows)} />
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<MarkdownTableHarnessApp />)
}
