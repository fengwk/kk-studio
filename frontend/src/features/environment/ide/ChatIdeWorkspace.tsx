import type { PropsWithChildren } from 'react'
import { IdePanelProvider } from '@/features/environment/ide/IdePanelProvider'

/**
 * 仅属于 Chat 的持久 IDE 宿主。应用组合层始终挂载它；离开 Chat 时宿主 hidden + inert，
 * 不移动到其它渲染根，也不卸载 iframe。
 */
export function ChatIdeWorkspace({ active, children }: PropsWithChildren<{ active: boolean }>) {
  return (
    <IdePanelProvider active={active}>
      {children}
    </IdePanelProvider>
  )
}
