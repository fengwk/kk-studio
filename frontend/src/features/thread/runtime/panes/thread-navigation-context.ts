import { createContext } from 'react'

/**
 * Pane 内打开 Thread 的附加事实：压缩执行视图需要从 FORK 切点之后读取分支自己的 Entry，
 * 普通子代理视图没有这个游标。它只是导航参数，不复制任何执行状态。
 */
export interface ThreadNavigationOptions {
  afterEntryId?: string
}

/** 普通点击交给当前 pane；没有 pane 导航时保留独立 Thread 地址。 */
export const ThreadNavigationContext = createContext<
  ((threadId: string, options?: ThreadNavigationOptions) => void) | null
>(null)
