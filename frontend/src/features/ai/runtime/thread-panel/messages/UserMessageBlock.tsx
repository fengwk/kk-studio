import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/** 全宽的 user 内容块（角色由表面样式区分，不显示标题）。 */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className="thread-block thread-block-user">
      <div className="thread-block-body">{message.text}</div>
    </section>
  )
}
