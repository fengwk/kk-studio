import { isRecord } from '@/shared/lib/value'

/**
 * 把 wire 上的 JSON 节点（或仍为原文的字符串）收敛为对象。
 *
 * <p>契约中的结构字段是 JSON 节点；字符串分支只服务必须在 wire 上保持原文的字段
 * （例如模型产出的工具参数）与历史测试夹具。
 */
export function parsePayload(value: unknown): Record<string, unknown> {
  if (typeof value === 'string') {
    if (!value) {
      return {}
    }
    try {
      return asRecord(JSON.parse(value))
    } catch {
      return {}
    }
  }
  return asRecord(value)
}

export function asRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {}
}

export function getRecordList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map(asRecord).filter((item) => Object.keys(item).length > 0)
}
