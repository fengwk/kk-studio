import {
  ChatWorkspaceRoute,
  ChatsPage,
  CreateChatDialog,
  ResourceDeleteDialog,
  ResourceEditorDialog,
  ThreadWorkspaceRoute,
} from '@/app/ai-console/ai-extension'
import { TaskToolRendererLazy } from '@/app/ai-console/TaskToolRendererLazy'
import type { TrustedReactExtension } from '@/platform/extensions/types'

/** 非组件导出单独放文件，避免 React Fast Refresh 整页失效导致白屏。 */
export const aiExtension: TrustedReactExtension = {
  id: 'builtin.ai',
  pages: [
    {
      id: 'ai.chats',
      path: 'chats',
      component: ChatsPage,
      navGroup: 'ai',
      navItem: { label: 'Chat', labelKey: 'ai.nav.chats', order: 100 },
    },
    {
      id: 'ai.chat-workspace',
      path: 'chats/:chatId',
      component: ChatWorkspaceRoute,
      navGroup: 'ai',
      workspace: true,
    },
    {
      id: 'ai.thread-workspace',
      path: 'threads/:threadId',
      component: ThreadWorkspaceRoute,
      navGroup: 'ai',
      workspace: true,
    },
  ],
  dialogs: [
    { id: 'ai.create-chat', component: CreateChatDialog },
    { id: 'ai.resource-editor', component: ResourceEditorDialog },
    { id: 'ai.delete-resource', component: ResourceDeleteDialog },
  ],
  // rendererKey 必须与后端 TaskDescriptor 冻结的 'task' 精确相同；MessageList
  // 不做 name switch，一律通过 application contributions 按 rendererKey 分发。
  toolRenderers: [{
    id: 'task',
    component: TaskToolRendererLazy,
  }],
}
