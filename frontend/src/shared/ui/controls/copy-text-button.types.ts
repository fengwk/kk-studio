import type { ButtonProps } from './Button'

/** 固定尺寸的原地复制反馈；复制目标只使用完整 source，展示文字不参与身份。 */
export interface CopyTextButtonProps extends Omit<ButtonProps, 'children' | 'onClick' | 'title' | 'aria-label'> {
  source: string
  label: string
  copiedLabel: string
  failedLabel: string
}
