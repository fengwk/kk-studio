const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/**
 * 字节计数按 1024 进制缩放并保留一位小数（整数结果去掉 `.0`）。
 * wire 中是十进制字符串，本地上传大小是 number；非法或不安全整数保持原文，
 * 避免把未知值伪装成具体容量。
 */
export function formatByteCount(value: string | number): string {
  const bytes = typeof value === 'string' ? Number(value) : value
  if (!Number.isSafeInteger(bytes) || bytes < 0) {
    return String(value)
  }
  let amount = bytes
  let unit = 0
  while (amount >= 1024 && unit < BYTE_UNITS.length - 1) {
    amount /= 1024
    unit += 1
  }
  const text = unit === 0 ? String(amount) : amount.toFixed(1).replace(/\.0$/u, '')
  return `${text} ${BYTE_UNITS[unit]}`
}
