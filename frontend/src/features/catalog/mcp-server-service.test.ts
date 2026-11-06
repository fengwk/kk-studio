import { describe, expect, it } from 'vitest'
import { headersUpdate } from '@/features/catalog/mcp-server-service'

describe('headersUpdate', () => {
  // 意图：界面只有「替换 / 清除 / 保持」三种意图，wire 上分别是 SET（JSON 对象文本）、CLEAR 与 KEEP。
  it('maps the three header intents to SecretUpdate operations', () => {
    expect(headersUpdate({ Authorization: 'Bearer x', 'X-Trace': '1' }, false)).toEqual({
      operation: 'SET',
      value: '{"Authorization":"Bearer x","X-Trace":"1"}',
    })
    expect(headersUpdate(null, true)).toEqual({ operation: 'CLEAR' })
    expect(headersUpdate({}, true)).toEqual({ operation: 'CLEAR' })
    expect(headersUpdate(null, false)).toEqual({ operation: 'KEEP' })
    expect(headersUpdate({}, false)).toEqual({ operation: 'KEEP' })
  })
})
