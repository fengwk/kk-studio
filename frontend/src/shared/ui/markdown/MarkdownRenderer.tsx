import { memo, type ComponentProps, type ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeHighlight from 'rehype-highlight'
import { CodeBlock } from '@/shared/ui/markdown/CodeBlock'
import { MermaidBlock } from '@/shared/ui/markdown/MermaidBlock'
import { splitMarkdownSegments } from '@/shared/ui/markdown/splitMarkdownSegments'
import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github-dark.min.css'

export type MarkdownTone = 'default' | 'muted'

type ReactMarkdownProps = ComponentProps<typeof ReactMarkdown>

const REMARK_PLUGINS: NonNullable<ReactMarkdownProps['remarkPlugins']> = [remarkGfm, remarkMath]
const REHYPE_PLUGINS: NonNullable<ReactMarkdownProps['rehypePlugins']> = [
  [rehypeKatex, { throwOnError: false, strict: 'ignore', output: 'html' }],
  rehypeHighlight,
]
const MARKDOWN_COMPONENTS = createMarkdownComponents()

/** 单段 markdown：content 不变则跳过 re-parse（流式时前缀段不再重复解析） */
const MarkdownSegmentView = memo(
  function MarkdownSegmentView({ content }: { content: string }) {
    return (
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} rehypePlugins={REHYPE_PLUGINS} components={MARKDOWN_COMPONENTS}>
        {content}
      </ReactMarkdown>
    )
  },
  (prev, next) => prev.content === next.content,
)

/**
 * 通用 Markdown 渲染器。
 * - 闭合 mermaid fence 拆成稳定兄弟节点
 * - md 段按 content memo，降低流式 token 全量 parse 成本
 */
export const MarkdownRenderer = memo(function MarkdownRenderer({
  content,
  className,
  tone = 'default',
}: {
  content: string
  className?: string
  tone?: MarkdownTone
}) {
  const text = content ?? ''
  if (!text.trim()) {
    return null
  }

  const segments = splitMarkdownSegments(text)

  return (
    <div className={['md-root', tone === 'muted' ? 'md-tone-muted' : '', className].filter(Boolean).join(' ')}>
      {segments.map((segment) => {
        if (segment.type === 'mermaid') {
          return <MermaidBlock key={segment.key} code={segment.content} />
        }
        if (!segment.content.trim()) {
          return null
        }
        return <MarkdownSegmentView key={segment.key} content={segment.content} />
      })}
    </div>
  )
})

function createMarkdownComponents(): Components {
  return {
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noreferrer noopener">
        {children}
      </a>
    ),
    code: ({ className: codeClassName, children, ...props }) => {
      const language = /language-([a-z0-9_+-]+)/i.exec(codeClassName || '')?.[1]?.toLowerCase()
      const raw = nodeText(children).replace(/\n$/, '')
      const isBlock =
        Boolean(language)
        || Boolean(codeClassName && /\bhljs\b/.test(codeClassName))
        || raw.includes('\n')

      if (!isBlock) {
        return (
          <code className={['md-inline-code', codeClassName].filter(Boolean).join(' ')} {...props}>
            {children}
          </code>
        )
      }

      return (
        <CodeBlock source={raw} className={codeClassName}>
          {children}
        </CodeBlock>
      )
    },
    pre: ({ children }) => <>{children}</>,
  }
}

function nodeText(node: ReactNode): string {
  if (node == null || typeof node === 'boolean') {
    return ''
  }
  if (typeof node === 'string' || typeof node === 'number') {
    return String(node)
  }
  if (Array.isArray(node)) {
    return node.map(nodeText).join('')
  }
  if (typeof node === 'object' && 'props' in node) {
    return nodeText((node as { props?: { children?: ReactNode } }).props?.children)
  }
  return ''
}
