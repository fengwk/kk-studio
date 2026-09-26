import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { ResourceAttachmentChip } from '@/features/ai/runtime/thread-panel/messages/ResourceAttachmentChip'

/** 全宽的 user 内容块（角色由表面样式区分，不显示标题）。 */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className="thread-block thread-block-user">
      {(message.contents ?? [{ type: 'text', text: message.text }]).map((content, index) =>
        content.type === 'text' ? (
          content.text ? <div className="thread-block-body" key={index}>{content.text}</div> : null
        ) : (
          <div className="thread-user-attachments" key={index}>
            <ResourceAttachmentChip
              attachment={content.attachment}
            />
          </div>
        ),
      )}
    </section>
  )
}
