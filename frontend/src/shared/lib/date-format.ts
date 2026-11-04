/** 把 wire Instant（epoch 秒）格式化为本地 `YYYY-MM-DD HH:mm`；非法值显示 '-'。 */
export function formatBackendDate(value: number): string {
  const date = new Date(value * 1000)
  if (!Number.isFinite(date.getTime())) {
    return '-'
  }
  return `${String(date.getFullYear()).padStart(4, '0')}-${String(
    date.getMonth() + 1,
  ).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
