import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(resolve(process.cwd(), 'src', relativePath), 'utf8')
}

describe('AI extension composition architecture', () => {
  it('keeps the AI root directory-only and ComfyUI outside the AI extension', () => {
    const aiRoot = resolve(process.cwd(), 'src/features/ai')
    const entries = readdirSync(aiRoot, { withFileTypes: true })

    expect(entries.every((entry) => entry.isDirectory())).toBe(true)
    expect(entries.map((entry) => entry.name).sort()).toEqual([
      'catalog',
      'chat',
      'environment',
      'extensions',
      'runtime',
      'settings',
      'shared',
    ])

    const aiDefinition = source('features/ai/extensions/ai-extension.definition.ts')
    expect(aiDefinition).not.toMatch(/comfyui/i)

    const comfyuiDefinition = source(
      'features/comfyui/extensions/comfyui-extension.definition.ts',
    )
    expect(comfyuiDefinition).toContain("id: 'ai.comfyui'")
    expect(comfyuiDefinition).toContain("id: 'ai.comfyui-editor'")
    expect(comfyuiDefinition).toContain("id: 'ai.comfyui-delete'")

    const extensionHost = source('app/extension-host.ts')
    expect(extensionHost).toContain('host.register(aiExtension)')
    expect(extensionHost).toContain('host.register(comfyuiExtension)')
  })
})
