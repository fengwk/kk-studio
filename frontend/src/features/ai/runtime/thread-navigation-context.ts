import { createContext } from 'react'

/** 普通点击交给当前 pane；没有 pane 导航时保留独立 Thread 地址。 */
export const ThreadNavigationContext = createContext<((threadId: string) => void) | null>(null)
