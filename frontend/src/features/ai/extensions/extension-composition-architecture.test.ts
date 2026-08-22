import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { createApplicationExtensionHost } from '@/app/extension-host'
import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { comfyuiExtension } from '@/features/comfyui/extensions/comfyui-extension.definition'
import { settingsExtension } from '@/features/settings/settings-extension'

describe('AI extension composition architecture', () => {
  it('registers default and lazy feature routes through independent extensions', () => {
    expect(aiExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['ai.chats', 'chats'],
      ['ai.chat-workspace', 'chats/:chatId'],
      ['ai.agents', 'agents'],
      ['ai.models', 'models'],
      ['ai.providers', 'providers'],
      ['ai.environments', 'environments'],
    ])
    expect(aiExtension.dialogs?.map((dialog) => dialog.id)).toEqual([
      'ai.create-chat',
      'ai.resource-editor',
      'ai.delete-resource',
    ])
    // task renderer 必须通过 extension 注册（rendererKey = contribution id），
    // MessageList 不做 name switch，展开能力也由 contribution 声明。
    expect(aiExtension.toolRenderers?.map((renderer) => renderer.id)).toEqual(['task'])
    expect(aiExtension.toolRenderers?.[0]?.isExpandable).toBeTypeOf('function')
    expect(comfyuiExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['ai.comfyui', 'comfyui'],
    ])
    expect(canvasExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['canvas.home', 'canvas'],
      ['canvas.editor', 'canvas/:canvasId'],
    ])
    expect(comfyuiExtension.dialogs?.map((dialog) => dialog.id)).toEqual([
      'ai.comfyui-editor',
      'ai.comfyui-delete',
    ])
    // Settings 是独立 extension，不塞进 aiExtension。
    expect(settingsExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['settings.page', 'settings'],
    ])

    const host = createApplicationExtensionHost()
    expect(host.pages.list().map((page) => page.id)).toEqual([
      'ai.chats',
      'ai.chat-workspace',
      'ai.agents',
      'ai.models',
      'ai.providers',
      'ai.environments',
      'ai.comfyui',
      'settings.page',
      'canvas.home',
      'canvas.editor',
    ])
    expect(host.dialogs.list().map((dialog) => dialog.id)).toEqual([
      ...aiExtension.dialogs!.map((dialog) => dialog.id),
      ...comfyuiExtension.dialogs!.map((dialog) => dialog.id),
    ])
    expect(host.pages.get('canvas.home')?.component).toBe(
      canvasExtension.pages?.[0].component,
    )
    expect(host.pages.get('canvas.editor')?.component).toBe(
      canvasExtension.pages?.[1].component,
    )
    expect(host.pages.get('settings.page')?.component).toBe(
      settingsExtension.pages?.[0].component,
    )
  })

  it('keeps global dialog hooks context-only and feature controllers outside the default path', () => {
    const chatController = source('../chat/useChatPageController.ts')
    const chatContext = source('../chat/ChatRuntimeContext.ts')
    const catalogContext = source('../catalog/CatalogRuntimeContext.ts')
    const aiExtensionSource = source('./ai-extension.tsx')
    const aiDefinitionSource = source('./ai-extension.definition.ts')
    const createChatDialogSource = source('./CreateChatDialog.tsx')
    const taskRendererSource = source('./TaskToolRendererLazy.tsx')
    const comfyuiContext = source('../../comfyui/ComfyuiContext.ts')
    const comfyuiExtensionSource = source('../../comfyui/extensions/comfyui-extension.tsx')

    expect(chatController).not.toContain('@/features/ai/catalog')
    expect(chatController).toContain('@/shared/api/agent-service')
    expect(chatContext).toContain(
      "import type { ChatPageController } from '@/features/ai/chat/useChatPageController'",
    )
    expect(catalogContext).toContain(
      "import type { CatalogPageController } from '@/features/ai/catalog/useCatalogPageController'",
    )
    expect(createChatDialogSource).toContain(
      "from '@/features/ai/chat/ChatRuntimeContext'",
    )
    expect(aiExtensionSource).toContain(
      "from '@/features/ai/catalog/CatalogRuntimeContext'",
    )
    expect(aiExtensionSource).toContain("import('@/features/ai/chat/ChatsRoute')")
    expect(aiExtensionSource).toContain("import('@/features/ai/chat/ChatWorkspaceRoute')")
    expect(aiDefinitionSource).not.toContain(
      "@/features/ai/runtime/thread-panel/messages/TaskToolRenderer'",
    )
    expect(aiDefinitionSource).toContain(
      "from '@/features/ai/extensions/TaskToolRendererLazy'",
    )
    expect(taskRendererSource).toContain(
      "import('@/features/ai/runtime/thread-panel/messages/TaskToolRenderer')",
    )
    expect(aiExtensionSource).not.toContain('useCatalogPageController')
    expect(comfyuiContext).toContain(
      "import type { ComfyuiPageController } from '@/features/comfyui/useComfyuiPageController'",
    )
    expect(comfyuiExtensionSource).toContain(
      "from '@/features/comfyui/ComfyuiContext'",
    )
    expect(comfyuiExtensionSource).not.toContain('useComfyuiPageController')
  })
})

function source(relativePath: string) {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8')
}
