import { useRef, type RefObject } from 'react'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'

/**
 * IDE 绑定操作的串行队列。
 *
 * <p>同一连接内只放行一个在途绑定操作，其余排队；旧连接的在途操作不阻塞新连接立即对账，
 * 因此连接换代时整队作废并递增 generation，让旧任务收尾时不再放行下一项。
 */
export function useIdeOperationQueue(
  identityRef: RefObject<ApplicationEventConnectionIdentity | null>,
  mountedRef: RefObject<boolean>,
) {
  const bindingRef = useRef(false)
  const operationConnectionRef = useRef<string | null>(null)
  const operationGenerationRef = useRef(0)
  const operationQueueRef = useRef<Array<() => Promise<void>>>([])

  function runExclusive(task: () => Promise<void>): void {
    const connectionId = identityRef.current?.connectionId ?? null
    if (bindingRef.current && operationConnectionRef.current === connectionId) {
      operationQueueRef.current.push(task)
      return
    }
    const generation = operationGenerationRef.current + 1
    operationGenerationRef.current = generation
    bindingRef.current = true
    operationConnectionRef.current = connectionId
    void (async () => {
      try {
        await task()
      } finally {
        if (operationGenerationRef.current === generation) {
          bindingRef.current = false
          operationConnectionRef.current = null
          const next = operationQueueRef.current.shift()
          if (next != null && mountedRef.current) {
            runExclusive(next)
          }
        }
      }
    })()
  }

  /** 连接换代或全量重置：作废在途操作与队列，新连接不排在旧请求后面。 */
  function invalidateOperations(): void {
    operationGenerationRef.current += 1
    bindingRef.current = false
    operationConnectionRef.current = null
    operationQueueRef.current = []
  }

  return { runExclusive, invalidateOperations }
}
