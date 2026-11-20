import { memo, useEffect, useId, useRef, useState } from 'react'
import type { Theme } from '@fengwk/kchart'
import { CodeBlock, CopyableShell } from '@/shared/ui/markdown/CodeBlock'
import { useI18n } from '@/shared/i18n'
import './mermaid-block.css'

type KChart = typeof import('@fengwk/kchart')
type Measure = (text: string, size: number, weight: number, fontFamily: string) => number
type Renderer = { kchart: KChart; theme: Theme; measure?: Measure }

/**
 * KChart 暗色 Terminal 主题换成应用调色板。KChart 只接受具体颜色，每个值重复旁注 token 的 palette 色值；
 * 调色板没有对应项的角色沿用 Terminal：中间层级的文字灰、粉/紫色 classDef 映射到的紫色和数据系列色。
 */
const PALETTE_ROLES = {
  bg: '#0d0f0e', // --stage-bg：对话区背景，图直接画在上面
  surface: '#101412', // --bg
  surface2: '#202723', // --surface-raised：节点填充
  line: '#2b342f', // --border
  line2: '#355344', // --accent-border：节点描边
  fg1: '#edf2ed', // --fg
  fg3: '#9aa59d', // --fg-muted
  accent: '#71e79a', // --accent
  ok: '#7fa38c', // --palette-moss-300：与 accent 区分，甘特图的已完成与关键任务才不同色
  warn: '#f3ae67', // --warning
  danger: '#f08585', // --danger
  info: '#77c7f5', // --info
  ext: '#9aa59d', // --fg-muted
}
/** 与 --font 相同的字体栈：测量和绘制使用同一字体。 */
const FONT_FAMILY =
  'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Helvetica Neue", sans-serif'

let rendererPromise: Promise<Renderer> | null = null
const svgCache = new Map<string, string>()
const SVG_TEMPLATE_PREFIX = 'kk-mermaid-template'
const SVG_REFERENCE_ATTRIBUTES = ['id', 'aria-labelledby', 'aria-describedby', 'clip-path', 'marker-start', 'marker-mid', 'marker-end', 'fill', 'stroke']
const SVG_REFERENCE_SELECTOR = SVG_REFERENCE_ATTRIBUTES.map((name) =>
  name === 'fill' || name === 'stroke' ? `[${name}^="url(#${SVG_TEMPLATE_PREFIX}-"]` : `[${name}]`,
).join(',')

function loadRenderer(): Promise<Renderer> {
  rendererPromise ??= import('@fengwk/kchart').then((kchart) => {
    const base = kchart.themes.terminal
    const theme: Theme = {
      ...base,
      ...PALETTE_ROLES,
      id: 'kk-studio',
      fontFamily: FONT_FAMILY,
      style: { ...base.style, radius: 8, smallRadius: 6 },
    }
    return { kchart, theme, measure: canvasMeasure() }
  })
  return rendererPromise
}

/** 按浏览器实际字形宽度折行和定框。 */
function canvasMeasure(): Measure | undefined {
  const context = document.createElement('canvas').getContext('2d')
  if (!context) {
    return undefined
  }
  return (text, size, weight, fontFamily) => {
    context.font = `${weight} ${size}px ${fontFamily}`
    return context.measureText(text).width
  }
}

/** KChart 按给定宽度排版；放不下时才加宽并在框内横向滚动。 */
async function renderSvg(source: string, width: number): Promise<string | null> {
  const { kchart, theme, measure } = await loadRenderer()
  const { svg } = await kchart.diagram(source, { width, theme, frame: false, measure, prefix: SVG_TEMPLATE_PREFIX })
  return svg
}

/** 缓存共用布局；挂载前只重写 SVG 引用属性，让标题、标记和裁剪都指向当前图。 */
function mountSvg(host: HTMLElement, svg: string, prefix: string) {
  const template = document.createElement('template')
  template.innerHTML = svg
  for (const element of template.content.querySelectorAll(SVG_REFERENCE_SELECTOR)) {
    for (const name of SVG_REFERENCE_ATTRIBUTES) {
      const value = element.getAttribute(name)
      if (value?.includes(`${SVG_TEMPLATE_PREFIX}-`)) {
        element.setAttribute(name, value.replaceAll(`${SVG_TEMPLATE_PREFIX}-`, `${prefix}-`))
      }
    }
  }
  // 图内链接与 Markdown 链接一致：新标签页打开，不带 opener 和 referrer。
  for (const link of template.content.querySelectorAll('a')) {
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noreferrer noopener')
  }
  host.replaceChildren(template.content)
}

function remember(key: string, svg: string) {
  svgCache.set(key, svg)
  if (svgCache.size > 48) {
    const first = svgCache.keys().next().value
    if (first !== undefined) {
      svgCache.delete(first)
    }
  }
}

function SourceView({ code }: { code: string }) {
  return (
    <CodeBlock source={code} className="language-mermaid">
      {code}
    </CodeBlock>
  )
}

/**
 * 闭合 mermaid fence 的稳定渲染节点：按容器宽度成图，宽度变化时重绘；同一源码和宽度只渲染一次。
 */
export const MermaidBlock = memo(function MermaidBlock({ code }: { code: string }) {
  const { t } = useI18n()
  const hostRef = useRef<HTMLDivElement>(null)
  const diagramPrefix = `kk-mermaid-${useId().replace(/[^A-Za-z0-9_-]/g, '')}`
  const source = code.trim()
  const [failedSource, setFailedSource] = useState<string | null>(null)
  const failed = !source || failedSource === source

  useEffect(() => {
    const host = hostRef.current
    if (!host || !source) {
      return
    }
    let cancelled = false
    let width = 0
    const draw = (next: number) => {
      if (next <= 0 || next === width) {
        return
      }
      width = next
      const key = `${next}\n${source}`
      const cached = svgCache.get(key)
      if (cached !== undefined) {
        mountSvg(host, cached, diagramPrefix)
        return
      }
      renderSvg(source, next).then(
        (svg) => {
          if (cancelled || width !== next) {
            return
          }
          if (svg === null) {
            setFailedSource(source)
            return
          }
          mountSvg(host, svg, diagramPrefix)
          remember(key, svg)
        },
        () => {
          if (!cancelled) {
            setFailedSource(source)
          }
        },
      )
    }
    const observer = new ResizeObserver((entries) => {
      const entry = entries[entries.length - 1]
      if (entry) {
        draw(Math.floor(entry.contentRect.width))
      }
    })
    observer.observe(host)
    return () => {
      cancelled = true
      observer.disconnect()
    }
  }, [source, diagramPrefix])

  if (failed) {
    return <SourceView code={code} />
  }

  return (
    <CopyableShell source={source} className="md-mermaid-shell">
      <div ref={hostRef} className="md-mermaid" role="img" aria-label={t('shared.mermaidDiagram')} />
    </CopyableShell>
  )
})
