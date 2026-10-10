import type { ComponentType, ReactNode } from 'react'
import type { PageContribution } from '@/platform/extensions/types'

export interface PrimaryNavItem {
  id: string
  groupId: string
  to: string
  labelKey: string
  ariaKey: string
  shortLabel: string
  icon: ComponentType<{ 'aria-hidden'?: boolean | 'true' | 'false' }>
}

export interface AppShellProps {
  children?: ReactNode
  pages?: readonly PageContribution[]
  navItems: readonly PrimaryNavItem[]
  /**
   * 固定在 stage 之后的底部面板槽（如全局终端面板）。由 app 组合根提供，
   * platform 不 import features；与 stage 共享垂直空间，不做 fixed 覆盖。
   */
  bottomPanel?: ReactNode
}
