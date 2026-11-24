import { MetaMessageBlock as ProjectMetaMessageBlock } from '@preview-original-turn-footer'
import type { MetaDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { MessageSquarePlus } from 'lucide-react'
import { IconButton } from '@/shared/ui/controls/IconButton'

const forkIntoNewSession = '从此处分叉开启一个新的会话'

export function MetaMessageBlock({ message }: { message: MetaDialogueMessage }) {
  if (message.endEntryId == null) return <ProjectMetaMessageBlock message={message} />
  return <div className="x-turn-footer" data-note="回合结束处可在本会话分支，也可复制到新会话。两种操作都只继承到这一轮末尾。">
    <ProjectMetaMessageBlock message={message} />
    <IconButton size="compact" label="从此处新建会话" aria-description={forkIntoNewSession}
      title={forkIntoNewSession} data-action="turn-new-session" data-turn-end-index={message.endEntryId}>
      <MessageSquarePlus aria-hidden="true" />
    </IconButton>
  </div>
}
