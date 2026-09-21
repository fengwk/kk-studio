/** 报告脱敏：把疑似凭据的取值替换为固定掩码（Node 原生，无依赖）。 */

const MASK = '[REDACTED]'

// 凭据类键名（TEST_MINIMAX_*、各类 *_KEY/*_TOKEN/*_PASSWORD）。键名本身保留，
// 只替换其后同一赋值/JSON 字段里的值；普通键（如 baseUrl）原样保留。
const SECRET_KEY = '[A-Za-z0-9_]*(?:API_KEY|ACCESS_KEY|SECRET_KEY|PASSWORD|GATEWAY_TOKEN|DAEMON_TOKEN|TOKEN)[A-Za-z0-9_]*'
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
