import { useContext, type ComponentProps } from 'react'
import { ThreadNavigationContext } from './thread-navigation-context'
import './thread-inspection-button.css'

/** 内部执行只在当前 pane 查看；压缩切点随查看层传递。 */
export function ThreadInspectionButton({
  threadId,
  afterEntryId,
  className,
  ...props
}: Omit<ComponentProps<'button'>, 'type' | 'onClick'> & { threadId: string; afterEntryId?: string }) {
  const inspect = useContext(ThreadNavigationContext)
  if (inspect == null) {
    return null
  }
  return (
    <button
      {...props}
      type="button"
      className={['thread-inspection-button', className].filter(Boolean).join(' ')}
      onClick={(event) => {
        if (event.defaultPrevented) {
          return
        }
        if (afterEntryId) {
          inspect(threadId, { afterEntryId })
        } else {
          inspect(threadId)
        }
      }}
    />
  )
}
