export interface ResourceGridLayout {
  rows: number
  cols: number
}

/**
 * 固定尺寸资源宫格：优先接近正方形，其次减少空位；同分时优先横向布局，
 * 再优先较少列。CSS Grid 按原序填充，末行自然左对齐。
 */
export function resourceGridLayout(count: number): ResourceGridLayout {
  if (!Number.isInteger(count) || count <= 0) {
    throw new RangeError('count must be a positive integer')
  }
  let best: ResourceGridLayout & { score: number } | null = null
  const maxRows = Math.ceil(Math.sqrt(count))
  for (let rows = 1; rows <= maxRows; rows += 1) {
    const cols = Math.ceil(count / rows)
    const empty = rows * cols - count
    const score = Math.abs(rows - cols) * 2 + empty
    const candidate = { rows, cols, score }
    if (!best || isBetterLayout(candidate, best)) {
      best = candidate
    }
  }
  return { rows: best!.rows, cols: best!.cols }
}

function isBetterLayout(
  candidate: ResourceGridLayout & { score: number },
  current: ResourceGridLayout & { score: number },
): boolean {
  if (candidate.score !== current.score) {
    return candidate.score < current.score
  }
  const candidateLandscape = candidate.cols >= candidate.rows
  const currentLandscape = current.cols >= current.rows
  if (candidateLandscape !== currentLandscape) {
    return candidateLandscape
  }
  return candidate.cols < current.cols
}
