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
  navItems?: readonly PrimaryNavItem[]
}
