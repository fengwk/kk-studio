import { useCallback, useRef, useState } from 'react'
import { createUuid } from '@/shared/lib/uuid'

/**
 * 一个表单的幂等请求身份：requestKey 与提交时冻结的 payload 成对存在。
 *
 * 提交前冻结 payload；之后输入一旦偏离冻结值就换新 key，绝不让同一个 key 携带不同 payload。成功或关闭时整体重置。
 */
export function useKeyedRequest<P>() {
  const [requestKey, setRequestKey] = useState<string>(() => createUuid())
  const payloadRef = useRef<P | null>(null)

  const freeze = useCallback((payload: P) => {
    payloadRef.current = payload
  }, [])

  const invalidateIfChanged = useCallback((stillMatches: (frozen: P) => boolean) => {
    if (payloadRef.current && !stillMatches(payloadRef.current)) {
      setRequestKey(createUuid())
      payloadRef.current = null
    }
  }, [])

  const reset = useCallback(() => {
    payloadRef.current = null
    setRequestKey(createUuid())
  }, [])

  return { requestKey, freeze, invalidateIfChanged, reset }
}
