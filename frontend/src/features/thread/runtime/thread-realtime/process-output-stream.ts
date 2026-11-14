import { isRecord } from '@/shared/lib/value'
import type { RealtimeToolStream } from '@/features/thread/runtime/thread-realtime/tool-stream'

export const PROCESS_OUTPUT_MAX_CHARS = 512 * 1024

export const PROCESS_OUTPUT_MAX_LINES = 2000

export const PROCESS_OUTPUT_OMISSION_MARKER = '... [output omitted] ...'

export interface ProcessOutputDetails {
  kind: 'process.output'
  mode: 'APPEND' | 'SNAPSHOT'
  startOffset: number
  endOffset: number
  observedBytes: number
}

export function parseProcessOutputDetails(details: unknown): ProcessOutputDetails | null {
  if (!isRecord(details) || details.kind !== 'process.output') {
    return null
  }
  const record = details
  const rawMode = typeof record.mode === 'string' ? record.mode.toUpperCase() : ''
  if (rawMode !== 'APPEND' && rawMode !== 'SNAPSHOT') {
    return null
  }
  const mode = rawMode as 'APPEND' | 'SNAPSHOT'
  const startOffset =
    typeof record.startOffset === 'number'
    && Number.isSafeInteger(record.startOffset)
    && record.startOffset >= 0
      ? record.startOffset
      : null
  const endOffset =
    typeof record.endOffset === 'number'
    && Number.isSafeInteger(record.endOffset)
    && record.endOffset >= 0
      ? record.endOffset
      : null
  const observedBytes =
    typeof record.observedBytes === 'number'
    && Number.isSafeInteger(record.observedBytes)
    && record.observedBytes >= 0
      ? record.observedBytes
      : null

  if (
    startOffset == null
    || endOffset == null
    || observedBytes == null
    || endOffset < startOffset
  ) {
    return null
  }

  return {
    kind: 'process.output',
    mode,
    startOffset,
    endOffset,
    observedBytes,
  }
}

export function isProcessOutputPartial(payload: Record<string, unknown>): boolean {
  return parseProcessOutputDetails(payload.details) != null
}

export function boundProcessOutputText(
  rawText: string,
  forceOmissionMarker = false,
): { text: string; hasOmittedPrefix: boolean } {
  let content = rawText
  let hasOmission = forceOmissionMarker
  if (content.startsWith(PROCESS_OUTPUT_OMISSION_MARKER + '\n')) {
    content = content.slice(PROCESS_OUTPUT_OMISSION_MARKER.length + 1)
    hasOmission = true
  } else if (content === PROCESS_OUTPUT_OMISSION_MARKER) {
    content = ''
    hasOmission = true
  }

  const lines = content.split('\n')
  if (lines.length > PROCESS_OUTPUT_MAX_LINES) {
    content = lines.slice(-PROCESS_OUTPUT_MAX_LINES).join('\n')
    hasOmission = true
  }

  if (content.length > PROCESS_OUTPUT_MAX_CHARS) {
    let sliced = content.slice(-PROCESS_OUTPUT_MAX_CHARS)
    const firstNewline = sliced.indexOf('\n')
    if (firstNewline !== -1 && firstNewline < 1024) {
      sliced = sliced.slice(firstNewline + 1)
    }
    content = sliced
    hasOmission = true
  }

  const resultText = hasOmission
    ? (content ? `${PROCESS_OUTPUT_OMISSION_MARKER}\n${content}` : PROCESS_OUTPUT_OMISSION_MARKER)
    : content

  return {
    text: resultText,
    hasOmittedPrefix: hasOmission,
  }
}

/**
 * 将一条 TOOL_PARTIAL chunk 聚合到 overlay 中：只有 text/json chunk 会追加到 text。
 * 支持 process.output 结构化流（APPEND / SNAPSHOT / gap healing / offset 去重与 UI 有界裁剪）。
 * runtime 禁止 TOOL_PARTIAL 携带 Resource 内容，因此 attachments 不会在这里聚合——
 * 它们仅由 {@link snapshotToolStream} 从持久终止态 result 投影得到。
 * attempt 发生变化（即重试）时会替换掉之前所有的片段。

/**
 * 分类一次 SNAPSHOT 相对当前 overlay 的关系。
 *
 * <p>基于单调递增的 {@code observedBytes} 与字节偏移（{@code startOffset}/{@code endOffset}）
 * 结合事件时间戳（{@code createdAt}）判定快照的新鲜度与重置语义：
 *
 * <ul>
 *   <li>{@code STALE}：陈旧或重复快照。包括：
 *     1) 时间戳严格早于当前状态（乱序/重放到达）；
 *     2) 时间戳不晚于当前状态时声明了更小的结束偏移（流内乱序到达的历史帧）；
 *     3) 与当前已应用的 SNAPSHOT 具有相同区间且未观测到更多字节（重复快照）。
 *     陈旧快照绝不覆盖或回退已应用的文本与偏移，只允许单调加宽 {@code error}。
 *   <li>{@code RESET}：合法的新流重置快照。
 *     时间戳严格晚于当前状态，但声明了更小的结束偏移（例如新进程/命令从 0 开始）。
 *     此时必须干净重置 text、offsets、observedBytes 与 gapPending，建立全新流基线。
 *   <li>{@code FORWARD}：合法向前推进或修复 gap 的快照（偏移单调前进、APPEND 后的截断快照、或修复 pending gap）。
 * </ul>
 */
export function classifySnapshot(
  current: RealtimeToolStream,
  createdAt: number,
  details: ProcessOutputDetails,
): 'STALE' | 'RESET' | 'FORWARD' {
  const applied = current.processOutput
  if (applied == null) {
    return 'FORWARD'
  }

  // 1. 时间戳严格早于当前状态：必为乱序/重放的历史快照。
  if (createdAt < current.createdAt) {
    return 'STALE'
  }

  // 2. 结束偏移回退：
  if (details.endOffset < applied.endOffset) {
    // 仅当时间戳严格晚于当前状态时，才视为新流合法重置（如新命令从 0 开始）；
    // 否则（时间戳相同或更早）属于流内乱序旧快照，必须作为 STALE 丢弃。
    if (createdAt > current.createdAt) {
      return 'RESET'
    }
    return 'STALE'
  }

  // 3. 结束偏移相同：
  if (details.endOffset === applied.endOffset) {
    // 当前已处于 SNAPSHOT 且起止区间与 observedBytes 均未推进，且无待修复的 gap：视为重复快照。
    if (
      applied.mode === 'SNAPSHOT'
      && !applied.gapPending
      && details.startOffset === applied.startOffset
      && details.observedBytes <= applied.observedBytes
    ) {
      return 'STALE'
    }
    // 其余相同 endOffset 的情况（如 APPEND 后的截断快照、gapPending 修复、或更窄的 tail 预览）均属合法向前更新。
    return 'FORWARD'
  }

  // 4. endOffset > applied.endOffset：正常推进或修复 gap。
  return 'FORWARD'
}

/** 同一 attempt 的 process.output 时间戳只允许单调前进，避免乱序帧污染后续快照分类基线。 */
export function monotonicCreatedAt(current: number, candidate: number): number {
  return candidate > current ? candidate : current
}
