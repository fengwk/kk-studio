/** 报告脱敏：把疑似凭据的取值替换为固定掩码（Node 原生，无依赖）。 */

import { writeFileSync } from 'node:fs'

const MASK = '[REDACTED]'

// 凭据类键名（TEST_MINIMAX_*、各类 *_KEY/*_TOKEN/*_PASSWORD）。键名本身保留，
// 只替换其后同一赋值/JSON 字段里的值；普通键（如 baseUrl）原样保留。
const SECRET_KEY = '[A-Za-z0-9_]*(?:API_?KEY|ACCESS_?KEY|SECRET_?KEY|PASSWORD|GATEWAY_?TOKEN|DAEMON_?TOKEN|TOKEN)[A-Za-z0-9_]*'
const SECRET_KEY_PATTERN = new RegExp(`^${SECRET_KEY}$`, 'i')
const SECRET_KEY_VALUE_PATTERN = new RegExp(
  `("?${SECRET_KEY}"?)\\s*[:=]\\s*("[^"\\n]*"|[^\\s",}]+)`,
  'gi',
)
const AUTHORIZATION_PATTERN = /(Authorization\s*:\s*)(Bearer\s+)?\S+/gi

/**
 * 对任意文本做凭据掩码；报告与 summary 写盘前必须经过此函数。
 * 掩码只覆盖凭据类键的值部分与 Authorization 头，其余文本原样保留。
 */
export function redactSecrets(text) {
  if (text == null) return text
  let output = String(text)
  output = output.replace(AUTHORIZATION_PATTERN, `Authorization: ${MASK}`)
  output = output.replace(SECRET_KEY_VALUE_PATTERN, (match, key, value) => {
    const quoted = value.startsWith('"') && value.endsWith('"')
    return `${key}: ${quoted ? `"${MASK}"` : MASK}`
  })
  return output
}

/** JSON 值先脱敏再序列化，避免转义后的错误正文漏检；数字统计保持原值。 */
function redactReportValue(value) {
  if (typeof value === 'string') return redactSecrets(value)
  if (Array.isArray(value)) return value.map(redactReportValue)
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key,
      typeof item === 'string' && SECRET_KEY_PATTERN.test(key) ? MASK : redactReportValue(item),
    ]))
  }
  return value
}

/** 报告的唯一写盘入口：JSON 保持结构，文本先脱敏，二进制制品逐字节保留。 */
export function writeReportArtifact(file, content) {
  if (Buffer.isBuffer(content) || content instanceof Uint8Array) {
    writeFileSync(file, content)
    return
  }
  const text = String(content)
  const sanitized = String(file).endsWith('.json')
    ? `${JSON.stringify(redactReportValue(JSON.parse(text)), null, 2)}\n`
    : redactSecrets(text)
  writeFileSync(file, sanitized, 'utf8')
}
