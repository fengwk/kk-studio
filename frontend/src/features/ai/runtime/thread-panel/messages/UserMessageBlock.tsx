import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { ResourceAttachmentChip } from '@/features/ai/runtime/thread-panel/messages/ResourceAttachmentChip'

/** 全宽的 user 内容块（角色由表面样式区分，不显示标题）。 */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className="thread-block thread-block-user">
      {message.text ? <div className="thread-block-body">{message.text}</div> : null}
      {message.attachments && message.attachments.length > 0 ? (
        <div className="thread-user-attachments">
          {message.attachments.map((attachment, index) => (
            <ResourceAttachmentChip
              key={`${attachment.blobId ?? attachment.name}-${index}`}
              attachment={attachment}
            />
          ))}
        </div>
      ) : null}
    </section>
  )
}
