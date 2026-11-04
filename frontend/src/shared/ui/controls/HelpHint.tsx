import { HelpCircle } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import './controls.css'

export interface HelpHintProps {
  /** `?` 按钮的无障碍名称，例如“帮助：YOLO 执行方式”。 */
  label: string
  children: ReactNode
  className?: string
}

/**
 * 统一的 `?` 帮助提示：按钮可聚焦并用 Enter/Space 展开，说明文本通过
 * aria-controls/aria-describedby 关联，键盘与读屏用户均可获取。
 */
export function HelpHint({ label, children, className }: HelpHintProps) {
  const id = `help-hint-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const [open, setOpen] = useState(false)

  return (
    <span className={['ui-help-hint', className].filter(Boolean).join(' ')}>
      <button
        type="button"
        className="ui-help-hint-trigger"
        aria-label={label}
        aria-expanded={open}
        aria-controls={id}
        aria-describedby={open ? id : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <HelpCircle size={14} aria-hidden="true" />
      </button>
      <span id={id} className="ui-help-hint-text" role="note" hidden={!open}>
        {children}
      </span>
    </span>
  )
}
