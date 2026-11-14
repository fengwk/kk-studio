import { asRecord } from '@/features/thread/runtime/payload-json'
import { getString } from '@/shared/lib/value'
import type { UsageCost } from '@/features/thread/runtime/thread-timeline-types'

/** 读取投影的精确十进制文本：可选整数、可选小数，非负（后端金额不允许为负）。 */
const DECIMAL_PATTERN = /^\+?(\d+)(?:\.(\d+))?$/

interface DecimalParts {
  int: string
  frac: string
}

function parseDecimal(value: string): DecimalParts | null {
  const match = DECIMAL_PATTERN.exec(value.trim())
  if (match == null) {
    return null
  }
  return { int: match[1]!, frac: match[2] ?? '' }
}

/**
 * 两个十进制文本的精确求和（BigInt 对齐小数位，无浮点误差）。
 * 任一输入非法时返回 null，绝不用近似值兜底。
 */
export function addDecimalStrings(left: string, right: string): string | null {
  const a = parseDecimal(left)
  const b = parseDecimal(right)
  if (a == null || b == null) {
    return null
  }
  const scale = Math.max(a.frac.length, b.frac.length)
  const sum =
    BigInt(a.int + a.frac.padEnd(scale, '0')) + BigInt(b.int + b.frac.padEnd(scale, '0'))
  const digits = sum.toString().padStart(scale + 1, '0')
  if (scale === 0) {
    return digits
  }
  const intPart = digits.slice(0, digits.length - scale)
  const fracPart = digits.slice(digits.length - scale).replace(/0+$/, '')
  return fracPart ? `${intPart}.${fracPart}` : intPart
}

/** 费用展示阈值（1e-6）：精确金额落在 (0, 1e-6) 时展示为该下界，而不是伪造成 0 或夸大成 0.000001。 */
const COST_DISPLAY_THRESHOLD = '0.000001'

/**
 * 精确十进制金额与阈值 1e-6 的比较：>0 大于、0 相等（含精确 0）、<0 小于、null 非法。
 * 用 BigInt 对齐小数位，不做任何浮点近似。
 */
function compareDecimalToThreshold(amount: string): number | null {
  const parts = parseDecimal(amount)
  if (parts == null) {
    return null
  }
  const scale = Math.max(parts.frac.length, 6)
  const value = BigInt(parts.int + parts.frac.padEnd(scale, '0'))
  if (value === 0n) {
    return 0
  }
  return value < 10n ** BigInt(scale - 6) ? -1 : 1
}

/**
 * 十进制金额的数值展示文本：统一四舍五入到最多 6 位小数并去掉尾随 0。
 * 只负责数值部分（不含货币与阈值下界）；非法输入返回 null。
 */
function formatDecimalAmount(amount: string): string | null {
  const parts = parseDecimal(amount)
  if (parts == null) {
    return null
  }
  // 保留 6 位展示 + 1 位用于 HALF_UP 进位。
  const padded = (parts.frac + '0000000').slice(0, 7)
  let scaled = BigInt(parts.int + padded.slice(0, 6))
  if (padded[6] != null && padded[6] >= '5') {
    scaled += 1n
  }
  const digits = scaled.toString().padStart(7, '0')
  const intPart = digits.slice(0, digits.length - 6)
  const fracPart = digits.slice(digits.length - 6).replace(/0+$/, '')
  return fracPart ? `${intPart}.${fracPart}` : intPart
}

const CURRENCY_SYMBOLS: Record<string, string | undefined> = {
  USD: '$',
  CNY: '¥',
  EUR: '€',
  GBP: '£',
}

/** 货币展示前缀：常见币种用符号，其余用 "CODE "。 */
function currencyPrefix(currency: string): string {
  const symbol = CURRENCY_SYMBOLS[currency]
  return symbol == null ? `${currency} ` : symbol
}

/**
 * 读取投影 -> 展示文本；null 表示无可用定价（绝不显示成 $0）。
 * 精确金额落在 (0, 1e-6) 时展示 `<$0.000001`："<" 在货币符号/代码之前。
 */
export function formatUsageCost(cost: UsageCost | null): string | null {
  if (cost == null || !cost.currency.trim()) {
    return null
  }
  const order = compareDecimalToThreshold(cost.amount)
  if (order == null) {
    return null
  }
  const prefix = currencyPrefix(cost.currency)
  if (order < 0) {
    return `<${prefix}${COST_DISPLAY_THRESHOLD}`
  }
  const amount = formatDecimalAmount(cost.amount)
  return amount == null ? null : `${prefix}${amount}`
}

/** 校验后端 usageCost 读取投影；currency/amount 任一非法即视为无定价。 */
export function normalizeUsageCost(value: unknown): UsageCost | null {
  const record = asRecord(value)
  const currency = getString(record.currency).trim()
  const amount = getString(record.amount).trim()
  if (!currency || parseDecimal(amount) == null) {
    return null
  }
  return { currency, amount }
}

/**
 * 合并两次调用的读取投影费用。
 * 缺失（未定价）或跨币种时返回 null：绝不给出误导性的完整总额，也绝不伪造成 $0。
 */
export function mergeUsageCost(left: UsageCost | null, right: UsageCost | null): UsageCost | null {
  if (left == null || right == null || left.currency !== right.currency) {
    return null
  }
  const amount = addDecimalStrings(left.amount, right.amount)
  return amount == null ? null : { currency: left.currency, amount }
}
