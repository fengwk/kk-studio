import { Bot, FolderKanban, Grid2X2, Settings, Wrench } from 'lucide-react'
import type { PrimaryNavItem } from '@/platform/shell/types'

/**
 * 应用组合根声明的顶层主导航固定组。
 * 平台外壳 AppShell 消费该列表，platform 自身不 import features。
 */
export const PRIMARY_NAV_ITEMS: readonly PrimaryNavItem[] = [
  {
    id: 'ai',
    groupId: 'ai',
    to: '/chats',
    labelKey: 'platform.nav.ai',
    ariaKey: 'platform.nav.aiAria',
    shortLabel: 'AI',
    icon: Bot,
  },
  {
    id: 'projects',
    groupId: 'projects',
    to: '/projects',
    labelKey: 'platform.nav.projects',
    ariaKey: 'platform.nav.projectsAria',
    shortLabel: 'Projects',
    icon: FolderKanban,
  },
  {
    id: 'canvas',
    groupId: 'canvas',
    to: '/canvas',
    labelKey: 'platform.nav.canvas',
    ariaKey: 'platform.nav.canvasAria',
    shortLabel: 'Canvas',
    icon: Grid2X2,
  },
  {
    id: 'tools',
    groupId: 'tools',
    to: '/comfyui',
    labelKey: 'platform.nav.tools',
    ariaKey: 'platform.nav.toolsAria',
    shortLabel: 'Tools',
    icon: Wrench,
  },
  {
    id: 'settings',
    groupId: 'settings',
    to: '/settings',
    labelKey: 'platform.nav.settings',
    ariaKey: 'platform.nav.settingsAria',
    shortLabel: 'Settings',
    icon: Settings,
  },
] as const
