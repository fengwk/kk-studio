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
    {
      id: 'ai.chats',
      path: 'chats',
      component: ChatsPage,
      navGroup: 'ai',
      navItem: { label: 'Chat', labelKey: 'ai.nav.chats', order: 100 },
      priority: 100,
    },
    {
      id: 'ai.chat-workspace',
      path: 'chats/:chatId',
      component: ChatWorkspaceRoute,
      navGroup: 'ai',
      workspace: true,
      priority: 100,
    },
    {
      id: 'ai.agents',
      path: 'agents',
      component: AgentsRoute,
      navGroup: 'ai',
      navItem: { label: 'Agent', labelKey: 'ai.nav.agents', order: 100 },
      priority: 100,
    },
    {
      id: 'ai.models',
      path: 'models',
      component: ModelsRoute,
      navGroup: 'ai',
      navItem: { label: 'Model', labelKey: 'ai.nav.models', order: 100 },
      priority: 100,
    },
    {
      id: 'ai.providers',
      path: 'providers',
      component: ProvidersRoute,
      navGroup: 'ai',
      navItem: { label: 'Provider', labelKey: 'ai.nav.providers', order: 100 },
      priority: 100,
    },
    {
      id: 'ai.skill-packages',
      path: 'skill-packages',
      component: SkillPackagesRoute,
      navGroup: 'ai',
      navItem: { label: 'Skill Packages', labelKey: 'ai.nav.skillPackages', order: 95 },
      priority: 100,
    },
    {
      id: 'ai.environments',
      path: 'environments',
      component: EnvironmentsRoute,
      navGroup: 'ai',
      navItem: { label: 'Environment', labelKey: 'ai.nav.environments', order: 90 },
      priority: 100,
    },
    {
      id: 'ai.mcp-servers',
      path: 'mcp-servers',
      component: McpServersRoute,
      navGroup: 'ai',
      navItem: { label: 'MCP Server', labelKey: 'ai.nav.mcpServers', order: 85 },
      priority: 100,
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
