import type { HTMLAttributes } from 'react'
import './data.css'

/** 行内等宽文本：标识符、路径、版本号和数据值。 */
export function Mono({ className, ...rest }: HTMLAttributes<HTMLElement>) {
  return <code className={['ui-mono', className].filter(Boolean).join(' ')} {...rest} />
}

/** 块级等宽文本：原样保留换行与缩进，横向滚动而不换行。 */
export function Code({ className, ...rest }: HTMLAttributes<HTMLPreElement>) {
  return <pre className={['ui-code', className].filter(Boolean).join(' ')} {...rest} />
}
