import { memo, useLayoutEffect, useId, useRef, useState } from 'react'
import type {
  ChartDiagnostic,
  ChartSpec,
  Scene,
  Theme,
} from '@fengwk/kchart'
import { CodeBlock, CopyableShell } from '@/shared/ui/markdown/CodeBlock'
import { useI18n } from '@/shared/i18n'
import type { VizSyntax } from '@/shared/ui/markdown/split-markdown-segments'
import './viz-block.css'

type KChart = typeof import('@fengwk/kchart')
type Measure = (text: string, size: number, weight: number, fontFamily: string) => number
type Renderer = { kchart: KChart; theme: Theme; measure?: Measure }

type CachedViz =
  | { kind: 'chart'; spec: ChartSpec; svg: string; scene: Scene }
  | { kind: 'diagram'; svg: string; scene: Scene }
type RenderFailure = { kind: 'failure'; detail?: string; line?: number }
type RenderOutcome = CachedViz | RenderFailure

/**
 * KChart 暗色 Terminal 主题换成应用调色板。KChart 只接受具体颜色，每个值重复旁注 token 的 palette 色值；
 * 调色板没有对应项的角色沿用 Terminal：中间层级的文字灰、粉/紫色 classDef 映射到的紫色和数据系列色。
 */
const PALETTE_ROLES = {
  bg: '#0d0f0e', // --stage-bg：对话区背景，图直接画在上面
  surface: '#101412', // --bg
  surface2: '#202723', // --surface-raised：节点填充
  line: '#2b342f', // --border
  line2: '#355344', // --palette-moss-700：节点描边
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

const MAX_LAYOUT_WIDTH = 720
let rendererPromise: Promise<Renderer> | null = null
const vizCache = new Map<string, CachedViz>()
const SVG_TEMPLATE_PREFIX = 'kk-viz-template'
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

type DiagramSyntax = Exclude<VizSyntax, 'kchart'>

/** 结构图按给定宽度排版；放不下时才加宽并在框内横向滚动。 */
async function renderDiagram(source: string, syntax: DiagramSyntax, width: number) {
  const { kchart, theme, measure } = await loadRenderer()
  const result = await kchart.diagram(source, {
    syntax,
    width,
    theme,
    frame: false,
    compact: true,
    measure,
    prefix: SVG_TEMPLATE_PREFIX,
  })
  if (result.svg === null) {
    const diagnostic = result.diagnostics.find(({ severity }) => severity === 'error')
    return {
      kind: 'failure' as const,
      detail: diagnostic ? diagnosticText(diagnostic) : undefined,
      line: diagnostic?.line,
    }
  }
  return { kind: 'diagram' as const, svg: result.svg, scene: result.scene }
}

/** 数据图声明是 JSON：先按原文解析，再把规格交给 KChart 渲染。 */
async function renderChart(source: string, width: number, lang: string): Promise<RenderOutcome> {
  let spec: ChartSpec
  try {
    spec = JSON.parse(source) as ChartSpec
  } catch (error) {
    return {
      kind: 'failure',
      detail: error instanceof Error ? error.message : String(error),
    }
  }
  const { kchart, theme, measure } = await loadRenderer()
  const result = await kchart.render(spec, {
    width,
    theme,
    lang,
    frame: false,
    measure,
    prefix: SVG_TEMPLATE_PREFIX,
  })
  if (result.svg === null) {
    const diagnostic = result.diagnostics[0]
    return {
      kind: 'failure',
      detail: diagnostic ? chartDiagnosticText(diagnostic) : undefined,
    }
  }
  return { kind: 'chart', spec, svg: result.svg, scene: result.scene }
}

/** 仅接收 KChart 序列化后的 SVG；重写实例引用，让标题、标记和裁剪都指向当前图。 */
function mountSvg(host: HTMLElement, svg: string, prefix: string): SVGSVGElement | null {
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
  return host.querySelector('svg')
}

function remember(key: string, result: CachedViz) {
  vizCache.set(key, result)
  if (vizCache.size > 48) {
    const first = vizCache.keys().next().value
    if (first !== undefined) {
      vizCache.delete(first)
    }
  }
}

const CJK_END = /[\u3000-\u303f\u3400-\u9fff\uf900-\ufaff\uff00-\uffef]$/
const HAS_CJK = /[\u3400-\u9fff\uf900-\ufaff]/

/** 诊断用图源的语言书写：按该语言补句末标点，中文句子直接相接，西文句子以空格分隔。 */
function diagnosticText({ message, fix }: { message?: string; fix?: string }) {
  return [message, fix]
    .map((part) => part?.trim())
    .filter((part): part is string => Boolean(part))
    .map((part) => (/[.!?。！？]$/.test(part) ? part : `${part}${HAS_CJK.test(part) ? '。' : '.'}`))
    .reduce((text, part) => (text ? `${text}${CJK_END.test(text) ? '' : ' '}${part}` : part), '')
}

function chartDiagnosticText({ path, message, fix }: ChartDiagnostic) {
  const text = diagnosticText({ message, fix })
  return path ? `${path}: ${text}` : text
}

function SourceView({ code, syntax }: { code: string; syntax: VizSyntax }) {
  return (
    <CodeBlock source={code} className={`language-${syntax}`}>
      {code}
    </CodeBlock>
  )
}

/** 按紧凑布局宽度重绘和缓存；数据图另显示标题、副标题、来源和注释。 */
export const VizBlock = memo(function VizBlock({ syntax, code }: { syntax: VizSyntax; code: string }) {
  const { locale } = useI18n()
  const source = code.trim()
  if (!source) {
    return <SourceView code={code} syntax={syntax} />
  }
  // 输入切换时同步丢弃旧的图与元信息；同一输入的宽度变化保留可观察的 host。
  return <VizFigure key={`${syntax}\n${locale}\n${source}`} syntax={syntax} code={code} source={source} locale={locale} />
})

function VizFigure({ syntax, code, source, locale }: { syntax: VizSyntax; code: string; source: string; locale: string }) {
  const { t } = useI18n()
  const hostRef = useRef<HTMLDivElement>(null)
  const vizPrefix = `kk-viz-${useId().replace(/[^A-Za-z0-9_-]/g, '')}`
  const [figure, setFigure] = useState<Scene | RenderFailure | null>(null)
  const failure = figure && 'kind' in figure ? figure : null
  const scene = figure && !('kind' in figure) ? figure : null

  useLayoutEffect(() => {
    const host = hostRef.current
    if (!host) {
      return
    }
    let cancelled = false
    let width = 0
    let drawAttempt = 0
    let attachAttempt = 0
    let disposeInteraction: (() => void) | null = null

    const releaseInteraction = () => {
      attachAttempt += 1
      disposeInteraction?.()
      disposeInteraction = null
    }

    const attachInteraction = (spec: ChartSpec, svg: SVGSVGElement) => {
      const attempt = ++attachAttempt
      const isCurrent = () => !cancelled && attempt === attachAttempt
      void loadRenderer().then(({ kchart }) => isCurrent() ? kchart.attach(svg, spec) : undefined).then(
        (dispose) => {
          if (!dispose) {
            return
          }
          if (!isCurrent()) {
            dispose()
            return
          }
          disposeInteraction = dispose
        },
        () => {
          // 交互不可用不影响已经成功绘制的图。
        },
      )
    }

    const show = (result: RenderOutcome) => {
      releaseInteraction()
      if (result.kind === 'failure') {
        host.replaceChildren()
        setFigure(result)
        return
      }
      const svg = mountSvg(host, result.svg, vizPrefix)
      setFigure(result.kind === 'chart' ? result.scene : null)
      if (svg && result.kind === 'chart' && Array.isArray(result.spec.interaction) && result.spec.interaction.length > 0) {
        attachInteraction(result.spec, svg)
      }
    }

    const draw = (next: number) => {
      if (cancelled || next <= 0 || next === width) {
        return
      }
      width = next
      const attempt = ++drawAttempt
      const isCurrent = () => !cancelled && attempt === drawAttempt
      const key = `${syntax}\n${locale}\n${next}\n${source}`
      const cached = vizCache.get(key)
      if (cached !== undefined) {
        show(cached)
        return
      }
      const rendering = syntax === 'kchart'
        ? renderChart(source, next, locale)
        : renderDiagram(source, syntax, next)
      rendering.then(
        (result) => {
          if (!isCurrent()) {
            return
          }
          show(result)
          if (result.kind !== 'failure') {
            remember(key, result)
          }
        },
        () => {
          if (isCurrent()) {
            show({ kind: 'failure' })
          }
        },
      )
    }
    const observer = new ResizeObserver((entries) => {
      const entry = entries[entries.length - 1]
      if (entry) {
        draw(Math.min(Math.floor(entry.contentRect.width), MAX_LAYOUT_WIDTH))
      }
    })
    observer.observe(host)
    return () => {
      cancelled = true
      releaseInteraction()
      observer.disconnect()
    }
  }, [source, syntax, locale, vizPrefix])

  return (
    <>
      <CopyableShell source={source} className={failure ? 'md-viz-shell md-viz-shell-failed' : 'md-viz-shell'}>
        {scene?.heading ? <div className="md-viz-heading">{scene.heading}</div> : null}
        {scene?.subtitle ? <div className="md-viz-subtitle">{scene.subtitle}</div> : null}
        <div ref={hostRef} className="md-viz" role={failure ? undefined : 'img'} aria-label={failure ? undefined : t('shared.vizFigure')} />
        {scene?.source || scene?.note ? (
          <div className="md-viz-meta">
            {scene.source ? (
              <div className="md-viz-source">{t('shared.vizSource', { source: scene.source })}</div>
            ) : null}
            {scene.note ? (
              <div className="md-viz-note">{t('shared.vizNote', { note: scene.note })}</div>
            ) : null}
          </div>
        ) : null}
      </CopyableShell>
      {failure ? (
        <>
          <SourceView code={code} syntax={syntax} />
          <div className="md-viz-error">
            {!failure.detail
              ? t('shared.vizRenderFailed')
              : Number.isInteger(failure.line) && failure.line! > 0
                ? t('shared.vizRenderFailedAt', { line: failure.line!, detail: failure.detail })
                : t('shared.vizRenderFailedWith', { detail: failure.detail })}
          </div>
        </>
      ) : null}
    </>
  )
}
