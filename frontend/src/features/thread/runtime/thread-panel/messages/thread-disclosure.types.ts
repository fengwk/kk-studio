import type { ComponentProps, ReactNode } from 'react'

/** 只读消息的布局契约；语义、配额和用户选择由消息适配器持有。 */
export interface ThreadDisclosureProps extends Omit<ComponentProps<'section'>, 'title' | 'children'> {
  header: ReactNode
  actions?: ReactNode
  tone: 'neutral' | 'info' | 'pending' | 'success' | 'error'
  expanded: boolean
  onExpandedChange: (expanded: boolean) => void
  /** 内容在收起态被截断或隐藏；header 的 CSS 截断由容器自行检测。 */
  hasDetails: boolean
  preview?: ReactNode
  body?: ReactNode
  label: string
}
