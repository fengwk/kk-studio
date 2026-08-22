import { lazy, Suspense } from 'react'
import type { ToolRendererProps } from '@/platform/extensions/types'

const TaskToolRenderer = lazy(async () => {
  const module = await import('@/features/ai/runtime/thread-panel/messages/TaskToolRenderer')
  return { default: module.TaskToolRenderer }
})

/** task 专属展示按首次出现的 task 消息加载，renderer key 与同步 expandability 契约保持不变。 */
export function TaskToolRendererLazy(props: ToolRendererProps) {
  return (
    <Suspense fallback={null}>
      <TaskToolRenderer {...props} />
    </Suspense>
  )
}
