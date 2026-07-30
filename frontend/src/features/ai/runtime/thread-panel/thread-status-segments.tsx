import { useLayoutEffect, useRef, useState } from 'react'
import type { ThreadStatusSegment } from '@/features/ai/runtime/thread-panel/thread-status-format'

/** 与 CSS `.thread-status-sep { margin: 0 8px }` + `|` 宽大致一致，略偏小以多装一点 */
const SEP_SLOT_PX = 16

/**
 * Responsive segment row layout for the thread status footer.
 *
 * - 宽够时 1 行；变窄 2+1 → 1+1+1
 * - │ 只画在行内
 * - 能 1 行绝不拆成多行（去掉反向滞回卡死）
 */
export function ThreadStatusSegmentRows({ segments }: { segments: ThreadStatusSegment[] }) {
  const hostRef = useRef<HTMLDivElement>(null)
  const [rows, setRows] = useState<number[][]>(() => [segments.map((_, i) => i)])

  useLayoutEffect(() => {
    const host = hostRef.current
    if (!host) {
      return
    }

    const repack = () => {
      const containerWidth = Math.max(0, host.clientWidth)
      // 尚无布局宽度时先按单行，避免用错误宽度锁死成 1+1+1
      if (containerWidth < 8) {
        const single = [segments.map((_, i) => i)]
        setRows((prev) => (sameRows(prev, single) ? prev : single))
        return
      }

      const font = readLineFont(host)
      const widths = segments.map((segment) => measureTextWidth(segment.text, font))
      const next = packRows(widths, containerWidth)
      setRows((prev) => (sameRows(prev, next) ? prev : next))
    }

    repack()
    const ro = new ResizeObserver(() => repack())
    ro.observe(host)
    if (document.fonts?.ready) {
      void document.fonts.ready.then(repack)
    }
    window.addEventListener('resize', repack)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', repack)
    }
  }, [segments])

  return (
    <div ref={hostRef} className="thread-status-line">
      {rows.map((row, rowIndex) => (
        <div key={`row-${rowIndex}-${row.join('-')}`} className="thread-status-row">
          {row.map((segIndex, pos) => {
            const segment = segments[segIndex]
            if (!segment) {
              return null
            }
            return (
              <span key={segment.key} className="thread-status-unit">
                {pos > 0 ? (
                  <span className="thread-status-sep" aria-hidden="true">
                    |
                  </span>
                ) : null}
                {segment.onClick || segment.onSecondaryClick ? (
                  <button
                    type="button"
                    className={`thread-status-seg is-clickable ${segment.className}`}
                    title={segment.title}
                    onClick={() => {
                      if (segment.onClick) {
                        segment.onClick()
                        return
                      }
                      segment.onSecondaryClick?.()
                    }}
                    onContextMenu={(event) => {
                      if (!segment.onSecondaryClick) {
                        return
                      }
                      event.preventDefault()
                      segment.onSecondaryClick()
                    }}
                  >
                    {segment.text}
                  </button>
                ) : (
                  <span className={`thread-status-seg ${segment.className}`} title={segment.title}>
                    {segment.text}
                  </span>
                )}
              </span>
            )
          })}
        </div>
      ))}
    </div>
  )
}

function readLineFont(host: HTMLElement): string {
  const style = window.getComputedStyle(host)
  const size = style.fontSize || '12px'
  const family = style.fontFamily || 'Inter, ui-sans-serif, system-ui, sans-serif'
  const weight = style.fontWeight || '400'
  return `${weight} ${size} ${family}`
}

function measureTextWidth(text: string, font: string): number {
  const canvas = measureTextWidth.canvas || (measureTextWidth.canvas = document.createElement('canvas'))
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return text.length * 7
  }
  ctx.font = font
  return Math.ceil(ctx.measureText(text).width)
}
measureTextWidth.canvas = null as HTMLCanvasElement | null

/** 从左到右装箱；装不下开新行（后段先掉下去 → 3 / 2+1 / 1+1+1） */
function packRows(naturalWidths: number[], containerWidth: number): number[][] {
  if (naturalWidths.length === 0) {
    return [[0]]
  }
  const rows: number[][] = []
  let current: number[] = []
  let rowWidth = 0

  for (let i = 0; i < naturalWidths.length; i += 1) {
    // 单段允许占满整行（再由 CSS ellipsis）
    const segW = Math.min(Math.max(naturalWidths[i] ?? 0, 1), containerWidth)
    if (current.length === 0) {
      current = [i]
      rowWidth = segW
      continue
    }
    const need = rowWidth + SEP_SLOT_PX + segW
    if (need > containerWidth) {
      rows.push(current)
      current = [i]
      rowWidth = segW
    } else {
      current.push(i)
      rowWidth = need
    }
  }
  if (current.length > 0) {
    rows.push(current)
  }
  return rows
}

function sameRows(left: number[][], right: number[][]): boolean {
  if (left.length !== right.length) {
    return false
  }
  return left.every(
    (row, index) =>
      row.length === right[index]?.length && row.every((value, j) => value === right[index]?.[j]),
  )
}
