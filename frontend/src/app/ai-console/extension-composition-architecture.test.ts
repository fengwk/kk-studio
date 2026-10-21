import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { applicationContributions } from '@/app/application-contributions'
import { aiExtension } from '@/app/ai-console/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { projectsExtension } from '@/features/projects/extensions/projects-extension.definition'
import { settingsExtension } from '@/features/settings/settings-extension'

describe('AI extension composition architecture', () => {
  it('registers default and lazy feature routes through independent extensions', () => {
    expect(aiExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['ai.chats', 'chats'],
      ['ai.chat-workspace', 'chats/:chatId'],
      ['ai.thread-workspace', 'threads/:threadId'],
      ['ai.agents', 'agents'],
      ['ai.models', 'models'],
      ['ai.providers', 'providers'],
      ['ai.skill-packages', 'skill-packages'],
      ['ai.environments', 'environments'],
      ['ai.mcp-servers', 'mcp-servers'],
    ])
    // 伪通用 Navigation registry 已彻底移除，AI 二级导航由 pages 的 metadata 本地派生
    expect('navigation' in aiExtension).toBe(false)
    expect(aiExtension.dialogs?.map((dialog) => dialog.id)).toEqual([
      'ai.create-chat',
      'ai.resource-editor',
      'ai.delete-resource',
    ])
    // task renderer 必须通过 extension 注册（rendererKey = contribution id），
    // MessageList 不做 name switch；展开/收起由宿主外壳统一负责，不再由 contribution 声明。
    expect(aiExtension.toolRenderers?.map((renderer) => renderer.id)).toEqual(['task'])
    expect(aiExtension.toolRenderers?.[0]?.component).toBeTypeOf('function')
    expect(canvasExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['canvas.home', 'canvas'],
      ['canvas.editor', 'canvas/:canvasId'],
    ])
    // Settings 是独立 extension，不塞进 aiExtension。
    expect(settingsExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['settings.page', 'settings'],
    ])
    expect(projectsExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['projects.home', 'projects'],
      ['projects.detail', 'projects/:projectId'],
    ])
    expect(applicationContributions.pages.map((page) => page.id)).toEqual([
      'ai.chats',
      'ai.chat-workspace',
      'ai.thread-workspace',
      'ai.agents',
      'ai.models',
      'ai.providers',
      'ai.skill-packages',
      'ai.environments',
      'ai.mcp-servers',
      'canvas.home',
      'canvas.editor',
      'projects.home',
      'projects.detail',
      'settings.page',
    ])
    expect(applicationContributions.dialogs.map((dialog) => dialog.id)).toEqual(
      aiExtension.dialogs!.map((dialog) => dialog.id),
    )
    expect(applicationContributions.overlays.map((overlay) => overlay.id)).toEqual(['projects.invalidation'])
    expect(applicationContributions.toolRenderers.get('task')?.component).toBe(
      aiExtension.toolRenderers?.[0].component,
    )
    expect(applicationContributions.pages.find((page) => page.id === 'canvas.home')?.component).toBe(
      canvasExtension.pages?.[0].component,
    )
    expect(applicationContributions.pages.find((page) => page.id === 'canvas.editor')?.component).toBe(
      canvasExtension.pages?.[1].component,
    )
    expect(applicationContributions.pages.find((page) => page.id === 'settings.page')?.component).toBe(
      settingsExtension.pages?.[0].component,
    )
  })

  it('keeps global dialog hooks context-only and feature controllers outside the default path', () => {
    const chatController = source('../../features/chat/useChatPageController.ts')
    const chatContext = source('../../features/chat/ChatRuntimeContext.ts')
    const catalogContext = source('../../features/catalog/CatalogRuntimeContext.ts')
    const aiExtensionSource = source('./ai-extension.tsx')
    const aiDefinitionSource = source('./ai-extension.definition.ts')
    const createChatDialogSource = source('./CreateChatDialog.tsx')
    const taskRendererSource = source('./TaskToolRendererLazy.tsx')

    expect(chatController).not.toContain('@/features/catalog')
    expect(chatController).toContain('@/shared/api/agent-service')
    expect(chatContext).toContain(
      "import type { ChatPageController } from '@/features/chat/useChatPageController'",
    )
    expect(catalogContext).toContain(
      "import type { CatalogPageController } from '@/features/catalog/useCatalogPageController'",
    )
    expect(createChatDialogSource).toContain(
      "from '@/features/chat/ChatRuntimeContext'",
    )
    expect(aiExtensionSource).toContain(
      "from '@/features/catalog/CatalogRuntimeContext'",
    )
    expect(aiExtensionSource).toContain("import('@/features/chat/ChatsRoute')")
    expect(aiExtensionSource).toContain("import('@/features/chat/ChatWorkspaceRoute')")
    expect(aiExtensionSource).toContain("import('@/features/chat/thread/ThreadWorkspaceRoute')")
    expect(aiDefinitionSource).not.toContain(
      "@/features/chat/runtime/thread-panel/messages/TaskToolRenderer'",
    )
    expect(aiDefinitionSource).toContain(
      "from '@/app/ai-console/TaskToolRendererLazy'",
    )
    expect(taskRendererSource).toContain(
      "import('@/features/chat/runtime/thread-panel/messages/TaskToolRenderer')",
    )
    expect(aiExtensionSource).not.toContain('useCatalogPageController')
  })
})

function source(relativePath: string) {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8')
}
