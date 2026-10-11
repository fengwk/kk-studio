import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 把 helper 子进程的原始 V8 覆盖率合并进 vitest 的行覆盖率，得到「父进程 + helper」的合并数字。
 *
 * <p>为什么不直接用 vitest 的 `coverage.autoAttachSubprocess`：它把子进程的原始偏移与父进程的 source map **在转换前**合并，
 * 而两条执行路径的坐标系不同（父进程跑 esbuild 产物、helper 直接跑 TS），合并会把同一次执行算成错位，数字反而失真。
 *
 * <p>这里的做法是各取所长：父进程侧沿用 vitest 已重映射到 TS 源的行覆盖率；helper 侧只挑「加载过 helper.ts」的原始文件
 * （helper.ts 只可能由 helper 子进程加载，测试进程从不 import 它），再用 Node 类型剥离保留位置这一事实把字节偏移折算成
 * 源文件行号，最后按行取并集。
 *
 * <p>用法：`node test/native/coverage-merge.mjs <coverage-final.json> <raw-v8-dir>`。
 */
const [, , istanbulPath, rawDir] = process.argv

/** 行号折算：把一个字节偏移映射到 1 起始的行号。 */
function lineIndex(text) {
  const starts = [0]
  for (let i = 0; i < text.length; i += 1) {
    if (text.charCodeAt(i) === 10) {
      starts.push(i + 1)
    }
  }
  return (offset) => {
    let low = 0
    let high = starts.length - 1
    while (low < high) {
      const mid = (low + high + 1) >> 1
      if (starts[mid] <= offset) {
        low = mid
      } else {
        high = mid - 1
      }
    }
    return low + 1
  }
}

/** 从 V8 函数区间计算被覆盖的行集合：某行取其最内层（跨度最小）包容区间的计数。 */
function coveredLines(text, functions) {
  const index = lineIndex(text)
  const ranges = []
  for (const fn of functions) {
    for (const range of fn.ranges) {
      ranges.push({ start: range.startOffset, end: range.endOffset, span: range.endOffset - range.startOffset, count: range.count })
    }
  }
  const lineStarts = []
  for (let line = 1; line <= text.split('\n').length; line += 1) {
    lineStarts.push(line)
  }
  const covered = new Set()
  for (const line of lineStarts) {
    const offset = lineStartOffset(text, line)
    let best = null
    for (const range of ranges) {
      if (range.start <= offset && offset < range.end && (best === null || range.span < best.span)) {
        best = range
      }
    }
    if (best !== null && best.count > 0) {
      covered.add(line)
    }
  }
  return covered
}

function lineStartOffset(text, line) {
  let seen = 1
  for (let i = 0; i < text.length; i += 1) {
    if (seen === line) {
      return i
    }
    if (text.charCodeAt(i) === 10) {
      seen += 1
    }
  }
  return text.length
}

/** helper 子进程原始覆盖率：只收「加载过 helper.ts」的进程，且只保留 src/native 下的 TS 脚本。 */
function helperRawCoverage(directory) {
  const result = new Map()
  for (const name of readdirSync(directory)) {
    if (!name.endsWith('.json')) {
      continue
    }
    let parsed
    try {
      parsed = JSON.parse(readFileSync(`${directory}/${name}`, 'utf8'))
    } catch {
      continue
    }
    const scripts = parsed.result ?? []
    const isHelper = scripts.some((script) => typeof script.url === 'string' && script.url.endsWith('/native/helper.ts'))
    if (!isHelper) {
      continue
    }
    for (const script of scripts) {
      if (typeof script.url !== 'string' || !script.url.startsWith('file://') || !script.url.endsWith('.ts')) {
        continue
      }
      const path = fileURLToPath(script.url)
      if (!path.includes('/daemon/src/native/')) {
        continue
      }
      let text
      try {
        text = readFileSync(path, 'utf8')
      } catch {
        continue
      }
      const covered = coveredLines(text, script.functions)
      const existing = result.get(path) ?? new Set()
      for (const line of covered) {
        existing.add(line)
      }
      result.set(path, existing)
    }
  }
  return result
}

const istanbul = JSON.parse(readFileSync(istanbulPath, 'utf8'))
const raw = helperRawCoverage(rawDir)

let totalLines = 0
let totalCovered = 0
let totalBranch = 0
let totalBranchCovered = 0
const rows = []
for (const [file, data] of Object.entries(istanbul)) {
  const statementLines = new Set()
  const covered = new Set()
  for (const [id, location] of Object.entries(data.statementMap)) {
    for (let line = location.start.line; line <= location.end.line; line += 1) {
      statementLines.add(line)
    }
    if (data.s[id] > 0) {
      for (let line = location.start.line; line <= location.end.line; line += 1) {
        covered.add(line)
      }
    }
  }
  for (const line of raw.get(file) ?? []) {
    covered.add(line)
  }
  let branchTotal = 0
  let branchCovered = 0
  for (const [id, meta] of Object.entries(data.branchMap)) {
    meta.locations.forEach((_, position) => {
      branchTotal += 1
      if ((data.b[id]?.[position] ?? 0) > 0) {
        branchCovered += 1
      }
    })
  }
  const lineCount = statementLines.size
  const lineHit = [...covered].filter((line) => statementLines.has(line)).length
  totalLines += lineCount
  totalCovered += lineHit
  totalBranch += branchTotal
  totalBranchCovered += branchCovered
  rows.push({ file: file.replace(/.*\/daemon\//, ''), linePct: lineCount === 0 ? 100 : (100 * lineHit) / lineCount, lineHit, lineCount, branchPct: branchTotal === 0 ? 100 : (100 * branchCovered) / branchTotal, branchCovered, branchTotal, uncovered: [...statementLines].filter((line) => !covered.has(line)).sort((a, b) => a - b) })
}
rows.sort((a, b) => a.file.localeCompare(b.file))
const withLines = process.env.KK_COVERAGE_LINES === '1'
for (const row of rows) {
  console.log(
    `${row.file.padEnd(34)} lines ${row.linePct.toFixed(1).padStart(5)}% (${row.lineHit}/${row.lineCount})  branch ${row.branchPct.toFixed(1).padStart(5)}% (${row.branchCovered}/${row.branchTotal})`,
  )
  if (withLines) {
    console.log(`    uncovered: ${row.uncovered.join(',')}`)
  }
}
console.log(
  `TOTAL lines ${((100 * totalCovered) / totalLines).toFixed(1)}% (${totalCovered}/${totalLines})  branch ${((100 * totalBranchCovered) / totalBranch).toFixed(1)}% (${totalBranchCovered}/${totalBranch})`,
)
