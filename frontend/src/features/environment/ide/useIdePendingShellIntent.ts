import { useRef } from 'react'

/**
 * 视图尚未建立时用户已请求的终端意图。
 *
 * <p>这类意图没有视图可以记录，只能挂在 environment 上；视图一建立就把它们取走
 * （`take`），因此同一个意图不会被两个视图重复消费。
 */
export function useIdePendingShellIntent() {
  const pendingShellEnvironmentsRef = useRef(new Set<string>())

  function remember(environmentId: string): void {
    pendingShellEnvironmentsRef.current.add(environmentId)
  }

  /** 取走该 environment 的挂起意图：返回是否曾请求过。 */
  function take(environmentId: string): boolean {
    return pendingShellEnvironmentsRef.current.delete(environmentId)
  }

  return { remember, take }
}
