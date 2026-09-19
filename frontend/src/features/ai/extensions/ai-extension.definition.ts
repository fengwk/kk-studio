import {
  AgentsRoute,
  ChatWorkspaceRoute,
  ChatsPage,
  CreateChatDialog,
  EnvironmentsRoute,
  McpServersRoute,
  ModelsRoute,
  ProvidersRoute,
  ResourceDeleteDialog,
  ResourceEditorDialog,
  SkillPackagesRoute,
} from '@/features/ai/extensions/ai-extension'
import { TaskToolRendererLazy } from '@/features/ai/extensions/TaskToolRendererLazy'
import { isTaskToolRendererExpandable } from '@/features/ai/runtime/thread-panel/messages/task-tool-display'
import type { TrustedReactExtension } from '@/platform/extensions/types'

/** 非组件导出单独放文件，避免 React Fast Refresh 整页失效导致白屏。 */
export const aiExtension: TrustedReactExtension = {
  id: 'builtin.ai',
  pages: [
    { id: 'ai.chats', path: 'chats', component: ChatsPage, priority: 100 },
    { id: 'ai.chat-workspace', path: 'chats/:chatId', component: ChatWorkspaceRoute, priority: 100 },
    { id: 'ai.agents', path: 'agents', component: AgentsRoute, priority: 100 },
    { id: 'ai.models', path: 'models', component: ModelsRoute, priority: 100 },
    { id: 'ai.providers', path: 'providers', component: ProvidersRoute, priority: 100 },
    { id: 'ai.skill-packages', path: 'skill-packages', component: SkillPackagesRoute, priority: 100 },
    { id: 'ai.environments', path: 'environments', component: EnvironmentsRoute, priority: 100 },
    { id: 'ai.mcp-servers', path: 'mcp-servers', component: McpServersRoute, priority: 100 },
  ],
  navigation: [
    { id: 'ai.nav.chats', label: 'Chat', labelKey: 'ai.nav.chats', path: 'chats', priority: 100 },
    { id: 'ai.nav.agents', label: 'Agent', labelKey: 'ai.nav.agents', path: 'agents', priority: 100 },
    { id: 'ai.nav.models', label: 'Model', labelKey: 'ai.nav.models', path: 'models', priority: 100 },
    { id: 'ai.nav.providers', label: 'Provider', labelKey: 'ai.nav.providers', path: 'providers', priority: 100 },
    {
      id: 'ai.nav.skill-packages',
      label: 'Skill Packages',
      labelKey: 'ai.nav.skillPackages',
      path: 'skill-packages',
      priority: 95,
    },
    {
      id: 'ai.nav.environments',
      label: 'Environment',
      labelKey: 'ai.nav.environments',
      path: 'environments',
      priority: 90,
    },
    {
      id: 'ai.nav.mcp-servers',
      label: 'MCP Server',
      labelKey: 'ai.nav.mcpServers',
      path: 'mcp-servers',
      priority: 85,
    },
  ],
  dialogs: [
    { id: 'ai.create-chat', component: CreateChatDialog },
    { id: 'ai.resource-editor', component: ResourceEditorDialog },
    { id: 'ai.delete-resource', component: ResourceDeleteDialog },
  ],
  // rendererKey 必须与后端 TaskDescriptor 冻结的 'task' 精确相同；MessageList
  // 不做 name switch，一律通过 extension host 按 rendererKey 分发。
  toolRenderers: [{
    id: 'task',
    component: TaskToolRendererLazy,
    isExpandable: isTaskToolRendererExpandable,
  }],
}
