import { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { AssistantMessageBlock } from '@/features/thread/runtime/thread-panel/messages/AssistantMessageBlock'
import { ToolMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ToolMessageBlock'
import type { TextDialogueMessage, ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { ToolRendererProps } from '@/platform/extensions/types'
import { setLocale } from '@/shared/i18n'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

// 语言由查询串决定：默认 zh-CN，`locale=en-US` 用于校验英文界面文案。
const isEnglish = new URLSearchParams(window.location.search).get('locale') === 'en-US'
setLocale(isEnglish ? 'en-US' : 'zh-CN')

/**
 * M1 表格复制的真实浏览器 harness：挂载真实 AssistantMessageBlock（含外层「复制全文」
 * 与共享 MarkdownRenderer），用窄表 + 图表分段 + 宽表覆盖只能在浏览器验证的契约：
 * 按钮与表头/单元格的几何关系、宽表横滚时按钮位置、外层 overflow 是否裁掉按钮、
 * hover/键盘显隐、剪贴板拿到的确实是表格原始 Markdown、流式追加后的分段稳定性。
 */

/** 允许浏览器用例在真实 Markdown → 图表路径注入语法与源码。 */
function vizSyntax(): string {
  return new URLSearchParams(window.location.search).get('syntax') ?? 'mermaid'
}

function vizSource(): string {
  const params = new URLSearchParams(window.location.search)
  return params.get('source') ?? params.get('mermaid') ?? 'graph TD; A-->B'
}

/** 窄表：富文本单元格（转义竖线/加粗/链接/行内代码），表头就是按钮必须避开的区域。 */
const NARROW_HEADER = '| 名称 | 值 | 备注 |'
const NARROW_ALIGN = '| :--- | ---: | :---: |'
const NARROW_BODY = '| `a\\|b` | **粗体** | [链接](https://example.com/x) |'

/** 追加行模拟流式增长；追加只改变第一段，图表之后的宽表段应保持已挂载。 */
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
    createdAt: Date.parse('2026-10-06T10:00:00Z'),
    status: 'done',
    text: [
      '## 表格复制检查',
      '',
      narrowTableSource(appendedRows),
      '',
      '普通段落。',
      '',
      `\`\`\`${vizSyntax()}`,
      vizSource(),
      '```',
      '',
      WIDE_TABLE,
      '',
      '结尾段落。',
    ].join('\n'),
  }
}

/** 代码表面夹具：行内代码 + 长行代码块，覆盖助手消息内的真实 Markdown 渲染。 */
const LONG_CODE_LINE = `const scope = '${'x'.repeat(160)}'`

function buildCodeMessage(): TextDialogueMessage {
  return {
    id: 'entry-code',
    role: 'assistant',
    subjectEntryId: 'entry-code',
    createdAt: Date.parse('2026-10-06T10:05:00Z'),
    status: 'done',
    text: [
      isEnglish ? 'Run `pnpm run dev` to start the server.' : '运行 `pnpm run dev` 启动服务。',
      '',
      isEnglish ? '> Read `config.json` before starting.' : '> 启动前请查看 `config.json`。',
      '',
      '```ts',
      'const total = input + output',
      LONG_CODE_LINE,
      '```',
      '',
    ].join('\n'),
  }
}

/** `.md-tone-muted`（思考正文）下的行内代码与代码块。 */
const MUTED_CODE_SOURCE = [
  isEnglish ? 'Reasoning with `inline-token`.' : '思考中的 `inline-token` 代码。',
  '',
  '```text',
  'muted code block',
  '```',
].join('\n')

// 用扩展渲染器接线验证 Markdown 工具结果，不改变内置工具的纯文本输出语义。
const TOOL_CALL: ToolDialogueMessage = {
  id: 'code-tool-call',
  role: 'tool',
  subjectEntryId: 'code-tool-call',
  createdAt: 0,
  status: 'done',
  phase: 'call',
  contents: [],
  toolCallId: 'code-tool',
  toolName: 'markdown_fixture',
  rendererKey: 'markdown_fixture',
  arguments: '{}',
}

const TOOL_RESULT: ToolDialogueMessage = {
  ...TOOL_CALL,
  id: 'code-tool-result',
  subjectEntryId: 'code-tool-result',
  phase: 'result',
  contents: [{ type: 'text', text: buildCodeMessage().text }],
}

function MarkdownToolResult({ message }: ToolRendererProps) {
  const content = message.contents[0]
  return content?.type === 'text' ? <MarkdownRenderer content={content.text} /> : null
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
  const showCode = new URLSearchParams(window.location.search).has('code')

  useEffect(() => {
    window.markdownTableHarness = {
      appendRow: () => setAppendedRows((count) => count + 1),
      narrowTableSource,
      wideTableSource: () => WIDE_TABLE,
    }
  }, [])

  return (
    <div style={{ width: 760, maxWidth: showCode ? '100%' : undefined, padding: 24 }}>
      <AssistantMessageBlock message={buildMessage(appendedRows)} />
      {showCode ? (
        <div id="markdown-code-fixture">
          <AssistantMessageBlock message={buildCodeMessage()} />
          <div id="markdown-muted-tone">
            <MarkdownRenderer tone="muted" content={MUTED_CODE_SOURCE} />
          </div>
          <div id="markdown-tool-result">
            <ToolMessageBlock message={TOOL_CALL} result={TOOL_RESULT} renderer={MarkdownToolResult} />
          </div>
        </div>
      ) : null}
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<MarkdownTableHarnessApp />)
}
