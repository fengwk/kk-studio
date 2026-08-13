import type {
  CSSProperties,
  MouseEventHandler,
  ReactNode,
} from 'react'

interface CanvasNodeContainerProps {
  as?: 'article' | 'section'
  className?: string
  icon: ReactNode
  typeLabel: string
  name: string
  accessories?: ReactNode
  bodyClassName?: string
  bodyStyle?: CSSProperties
  onBodyDoubleClick?: MouseEventHandler<HTMLDivElement>
  children: ReactNode
}

/**
 * Canvas 节点唯一外壳：统一标题与资源承载区。具体媒体、文本、音频和 Function
 * 只作为 Body renderer 挂载，不再各自定义卡片 chrome。
 */
export function CanvasNodeContainer({
  as: Element = 'article',
  className,
  icon,
  typeLabel,
  name,
  accessories,
  bodyClassName,
  bodyStyle,
  onBodyDoubleClick,
  children,
}: CanvasNodeContainerProps) {
  const title = `${typeLabel} | ${name}`
  return (
    <Element className={['canvas-node-container', className].filter(Boolean).join(' ')}>
      {accessories}
      <CanvasNodeHeader icon={icon} typeLabel={typeLabel} name={name} />
      <div
        className={['canvas-node-body', bodyClassName].filter(Boolean).join(' ')}
        style={bodyStyle}
        onDoubleClick={onBodyDoubleClick}
        aria-label={title}
      >
        {children}
      </div>
    </Element>
  )
}

export function CanvasNodeHeader({
  icon,
  typeLabel,
  name,
}: {
  icon: ReactNode
  typeLabel: string
  name: string
}) {
  const title = `${typeLabel} | ${name}`
  return (
    <header className="canvas-node-header" title={title} aria-label={title}>
      <span className="canvas-node-header-icon" aria-hidden="true">{icon}</span>
      <span className="canvas-node-type">{typeLabel}</span>
      <span className="canvas-node-header-separator" aria-hidden="true">|</span>
      <span className="canvas-node-name">{name}</span>
    </header>
  )
}
