import { describe, expect, it } from 'vitest'
import { applicationContributions } from '@/app/application-contributions'
import { aiExtension } from '@/app/ai-console/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'

describe('AI extension composition', () => {
  it('registers AI dialogs through the AI extension', () => {
    expect(aiExtension.pages).toEqual([])
    expect(aiExtension.dialogs?.map((dialog) => dialog.id)).toEqual([
      'ai.resource-editor',
      'ai.delete-resource',
    ])
    // task renderer 通过 extension 注册，rendererKey 与 contribution id 一致。
    expect(aiExtension.toolRenderers?.map((renderer) => renderer.id)).toEqual(['task'])
    expect(aiExtension.toolRenderers?.[0]?.component).toBeTypeOf('function')
    expect(canvasExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['canvas.editor', 'canvas/:canvasId'],
    ])
    expect(applicationContributions.pages.map((page) => page.id)).toEqual([
      'canvas.editor',
    ])
    expect(applicationContributions.dialogs.map((dialog) => dialog.id)).toEqual(
      aiExtension.dialogs!.map((dialog) => dialog.id),
    )
    expect(applicationContributions.toolRenderers.get('task')?.component).toBe(
      aiExtension.toolRenderers?.[0].component,
    )
    expect(applicationContributions.pages.find((page) => page.id === 'canvas.editor')?.component).toBe(
      canvasExtension.pages?.[0].component,
    )
  })
})
