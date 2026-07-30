import {
  AgentsPage,
  ChatWorkspaceRoute,
  ChatsPage,
  CreateChatDialog,
  EnvironmentsRoute,
  HarnessSettingsRoute,
  ModelsPage,
  ProvidersPage,
  ResourceDeleteDialog,
  ResourceEditorDialog,
} from '@/features/ai/extensions/ai-extension'
import type { TrustedReactExtension } from '@/platform/extensions/types'

/** 非组件导出单独放文件，避免 React Fast Refresh 整页失效导致白屏。 */
export const aiExtension: TrustedReactExtension = {
  id: 'builtin.ai',
  pages: [
    { id: 'ai.chats', path: 'chats', component: ChatsPage, priority: 100 },
    { id: 'ai.chat-workspace', path: 'chats/:chatId', component: ChatWorkspaceRoute, priority: 100 },
    { id: 'ai.agents', path: 'agents', component: AgentsPage, priority: 100 },
    { id: 'ai.models', path: 'models', component: ModelsPage, priority: 100 },
    { id: 'ai.providers', path: 'providers', component: ProvidersPage, priority: 100 },
    { id: 'ai.environments', path: 'environments', component: EnvironmentsRoute, priority: 100 },
    { id: 'ai.settings', path: 'settings', component: HarnessSettingsRoute, priority: 100 },
  ],
  navigation: [
    { id: 'ai.nav.chats', label: 'Chat', path: 'chats', priority: 100 },
    { id: 'ai.nav.agents', label: 'Agent', path: 'agents', priority: 100 },
    { id: 'ai.nav.models', label: 'Model', path: 'models', priority: 100 },
    { id: 'ai.nav.providers', label: 'Provider', path: 'providers', priority: 100 },
    { id: 'ai.nav.environments', label: 'Environment', path: 'environments', priority: 90 },
    { id: 'ai.nav.settings', label: '设置', path: 'settings', priority: 80 },
  ],
  dialogs: [
    { id: 'ai.create-chat', component: CreateChatDialog },
    { id: 'ai.resource-editor', component: ResourceEditorDialog },
    { id: 'ai.delete-resource', component: ResourceDeleteDialog },
  ],
}
