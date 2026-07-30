import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(resolve(process.cwd(), 'src', relativePath), 'utf8')
}

function productionSourceTree(relativePath: string): string {
  const directory = resolve(process.cwd(), 'src', relativePath)
  return readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const childPath = `${relativePath}/${entry.name}`
      if (entry.isDirectory()) {
        return productionSourceTree(childPath)
      }
      if (
        entry.isFile() &&
        /\.(ts|tsx)$/.test(entry.name) &&
        !entry.name.includes('.test.')
      ) {
        return source(childPath)
      }
      return []
    })
    .join('\n')
}

describe('AI extension composition architecture', () => {
  it('keeps the AI root bounded and ComfyUI independent from AI sources', () => {
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
    ])

    const aiDefinition = source('features/ai/extensions/ai-extension.definition.ts')
    const aiExtension = source('features/ai/extensions/ai-extension.tsx')
    expect(`${aiDefinition}\n${aiExtension}`).not.toMatch(/comfyui/i)
    expect(productionSourceTree('features/ai')).not.toContain(
      'useAiConsoleController()',
    )
    expect(aiExtension).toContain('useAiConsoleController(scope)')
    expect(aiExtension).toContain('<AiConsoleRuntime scope="chats">')
    expect(aiExtension).toContain('<AiConsoleRuntime scope="agents">')
    expect(aiExtension).toContain('<AiConsoleRuntime scope="models">')
    expect(aiExtension).toContain('<AiConsoleRuntime scope="providers">')

    expect(productionSourceTree('features/comfyui')).not.toContain('@/features/ai/')

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
