import { memo, useEffect, useId, useRef, useState } from 'react'
import { CodeBlock, CopyableShell } from '@/shared/ui/markdown/CodeBlock'
import { useI18n } from '@/shared/i18n'

type MermaidApi = {
  initialize: (config: Record<string, unknown>) => void
  render: (id: string, text: string) => Promise<{ svg: string }>
}

let mermaidModulePromise: Promise<MermaidApi> | null = null
let mermaidInitialized = false
const svgCache = new Map<string, string>()

/**
 * Mermaid 官方主题通道（theme + themeVariables + themeCSS）。
 * 面向「全部图」的产品主题，而不是针对某次 agent 输出的特判。
 * 若图源内使用 style/classDef 写死颜色，仍以图内容为准（内容层 > 主题层）。
 */
const MERMAID_CONFIG: Record<string, unknown> = {
  startOnLoad: false,
  suppressErrorRendering: true,
  securityLevel: 'strict',
  theme: 'base',
  fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
  themeVariables: {
    darkMode: true,
    background: 'transparent',
    fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
    fontSize: '14px',
    textColor: '#edf2ed',
    primaryColor: '#202723',
    primaryTextColor: '#edf2ed',
    primaryBorderColor: '#355344',
    secondaryColor: '#1b211e',
    secondaryTextColor: '#edf2ed',
    secondaryBorderColor: '#2b342f',
    tertiaryColor: '#173d28',
    tertiaryTextColor: '#edf2ed',
    tertiaryBorderColor: '#355344',
    lineColor: '#919c94',
    mainBkg: '#202723',
    nodeBkg: '#202723',
    nodeBorder: '#355344',
    clusterBkg: 'transparent',
    clusterBorder: '#2b342f',
    titleColor: '#edf2ed',
    edgeLabelBackground: 'transparent',
    nodeTextColor: '#edf2ed',
    actorBkg: '#202723',
    actorBorder: '#355344',
    actorTextColor: '#edf2ed',
    actorLineColor: '#919c94',
    signalColor: '#919c94',
    signalTextColor: '#edf2ed',
    labelBoxBkgColor: '#202723',
    labelBoxBorderColor: '#355344',
    labelTextColor: '#edf2ed',
    noteBkgColor: '#1b211e',
    noteTextColor: '#edf2ed',
    noteBorderColor: '#2b342f',
    // 离散系列（饼图/状态色阶）统一绿灰，避免默认高饱和彩虹色
    pie1: '#355344',
    pie2: '#4a6356',
    pie3: '#2b342f',
    pie4: '#173d28',
    pie5: '#5c7a68',
    pie6: '#202723',
    pie7: '#6d8f7a',
    pie8: '#1b211e',
    pie9: '#7fa38c',
    pie10: '#2b342f',
    pie11: '#919c94',
    pie12: '#355344',
    pieSectionTextColor: '#edf2ed',
    pieTitleTextColor: '#edf2ed',
    pieLegendTextColor: '#edf2ed',
    cScale0: '#355344',
    cScale1: '#4a6356',
    cScale2: '#2b342f',
    cScale3: '#173d28',
    cScale4: '#5c7a68',
    cScale5: '#202723',
    cScale6: '#6d8f7a',
    cScale7: '#1b211e',
    cScale8: '#7fa38c',
    cScale9: '#919c94',
    cScale10: '#355344',
    cScale11: '#4a6356',
  },
  themeCSS: `
    .node rect, .node circle, .node ellipse, .node polygon {
      fill: #202723 !important;
      stroke: #355344 !important;
    }
    .edgePath .path, .flowchart-link {
      stroke: #919c94 !important;
    }
    marker path {
      fill: #919c94 !important;
      stroke: #919c94 !important;
    }
    .edgeLabel rect {
      fill: transparent !important;
      stroke: transparent !important;
    }
    .cluster rect {
      fill: transparent !important;
      stroke: #2b342f !important;
    }
  `,
  flowchart: {
    htmlLabels: true,
    curve: 'basis',
    padding: 12,
  },
}

async function getMermaid(): Promise<MermaidApi> {
  if (!mermaidModulePromise) {
    mermaidModulePromise = import('mermaid').then((mod) => mod.default as unknown as MermaidApi)
  }
  const mermaid = await mermaidModulePromise
  if (!mermaidInitialized) {
    mermaid.initialize(MERMAID_CONFIG)
    mermaidInitialized = true
  }
  return mermaid
}

function SourceView({ code }: { code: string }) {
  return (
    <CodeBlock source={code} className="language-mermaid">
      {code}
    </CodeBlock>
  )
}

/**
 * 闭合 mermaid fence 的稳定渲染节点；相同 code 只成图一次。
 */
export const MermaidBlock = memo(function MermaidBlock({ code }: { code: string }) {
  const { t } = useI18n()
  const hostRef = useRef<HTMLDivElement>(null)
  const reactId = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const doneCodeRef = useRef<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const trimmed = code.trim()
    if (!trimmed) {
      setFailed(true)
      return
    }
    if (doneCodeRef.current === trimmed) {
      return
    }

    const host = hostRef.current
    if (!host) {
      return
    }

    const cached = svgCache.get(trimmed)
    if (cached) {
      host.innerHTML = cached
      doneCodeRef.current = trimmed
      setFailed(false)
      return
    }

    let cancelled = false
    void (async () => {
      try {
        const mermaid = await getMermaid()
        const renderId = `mmd${reactId}${Math.abs(hashCode(trimmed)).toString(36)}`
        const { svg } = await mermaid.render(renderId, trimmed)
        if (cancelled || !hostRef.current) {
          return
        }
        hostRef.current.innerHTML = svg
        svgCache.set(trimmed, hostRef.current.innerHTML)
        if (svgCache.size > 48) {
          const first = svgCache.keys().next().value
          if (first) {
            svgCache.delete(first)
          }
        }
        doneCodeRef.current = trimmed
        setFailed(false)
      } catch {
        if (!cancelled) {
          doneCodeRef.current = null
          setFailed(true)
        }
      }
    })()

    return () => {
      cancelled = true
    }
  }, [code, reactId])

  if (failed) {
    return <SourceView code={code} />
  }

  return (
    <CopyableShell source={code.trim()} className="md-mermaid-shell">
      <div
        ref={hostRef}
        className="md-mermaid"
        role="img"
        aria-label={t('shared.mermaidDiagram')}
      />
    </CopyableShell>
  )
})

function hashCode(value: string): number {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash << 5) - hash + value.charCodeAt(i)
    hash |= 0
  }
  return hash
}
