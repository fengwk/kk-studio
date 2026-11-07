/**
 * 时间显示的唯一格式：本地 `YYYY-MM-DD HH:mm`。卡片与详情元数据使用分钟精度；
 * 诊断时间线（如 Environment 事件日志）只在同一格式后追加 `:ss`。
 */

/** 把 wire Instant（epoch 毫秒）格式化为本地 `YYYY-MM-DD HH:mm`；非法值显示 '-'。 */
export function formatBackendDate(value: number): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) {
    return '-'
  }
  return formatLocalDateTime(date)
}

/** 诊断时间线用的同一固定格式，精确到秒；非法值显示 '-'。 */
export function formatBackendDateSeconds(value: number): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) {
    return '-'
  }
  return `${formatLocalDateTime(date)}:${String(date.getSeconds()).padStart(2, '0')}`
}

/** 本地日期时间，精确到分钟。调用方须传入有效日期。 */
function formatLocalDateTime(date: Date): string {
  return `${String(date.getFullYear()).padStart(4, '0')}-${String(
    date.getMonth() + 1,
  ).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/**
 * 解析历史 codec JSON 里的时间：只接受 Java `Instant.toString()` 的毫秒精度文本
 * （UTC `Z`，整秒无小数，否则恰好三位小数，例如 `2026-01-01T00:00:05Z`、
 * `2026-01-01T00:00:05.120Z`）。其他形态一律返回 null，由调用方按未知数据处理。
 */
export function parseCodecInstantMillis(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) {
    return null
  }
  const millis = Date.parse(value)
  if (!Number.isFinite(millis)) {
    return null
  }
  // 往返一致才接受：拒绝 2026-02-30 这类会被 Date 静默归一化的非规范文本。
  return new Date(millis).toISOString().replace('.000Z', 'Z') === value ? millis : null
}
