import { describe, expect, it } from 'vitest'
import { createApplicationExtensionHost } from '@/app/extension-host'
import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { comfyuiExtension } from '@/features/comfyui/extensions/comfyui-extension.definition'

describe('AI extension composition architecture', () => {
  it('registers default and lazy feature routes through independent extensions', () => {
    expect(aiExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['ai.chats', 'chats'],
      ['ai.chat-workspace', 'chats/:chatId'],
      ['ai.agents', 'agents'],
      ['ai.models', 'models'],
      ['ai.providers', 'providers'],
      ['ai.environments', 'environments'],
      ['ai.settings', 'settings'],
    ])
    expect(aiExtension.dialogs?.map((dialog) => dialog.id)).toEqual([
      'ai.create-chat',
      'ai.resource-editor',
      'ai.delete-resource',
    ])
    expect(comfyuiExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['ai.comfyui', 'comfyui'],
    ])
    expect(comfyuiExtension.dialogs?.map((dialog) => dialog.id)).toEqual([
      'ai.comfyui-editor',
      'ai.comfyui-delete',
    ])

    const host = createApplicationExtensionHost()
    expect(host.pages.list().map((page) => page.id)).toEqual([
      'ai.chats',
      'ai.chat-workspace',
      'ai.agents',
      'ai.models',
      'ai.providers',
      'ai.environments',
      'ai.settings',
      'ai.comfyui',
      'canvas.home',
    ])
    expect(host.dialogs.list().map((dialog) => dialog.id)).toEqual([
      ...aiExtension.dialogs!.map((dialog) => dialog.id),
      ...comfyuiExtension.dialogs!.map((dialog) => dialog.id),
    ])
    expect(host.pages.get('canvas.home')?.component).toBe(
      canvasExtension.pages?.[0].component,
    )
  })
})
