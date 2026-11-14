import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import {
  assembleSettingsUpdate,
  draftLeafPaths,
  settingsSectionsToDraft,
} from '@/features/settings/system-settings-draft'
import { validateSystemSettingsSchema } from '@/features/settings/system-settings-schema-validation'

function backendSource(path: string): string {
  return readFileSync(resolve(process.cwd(), '..', path), 'utf8')
}

describe('settings backend field contract', () => {
  // 对照后端字段事实，确保前端 DTO、草稿和 mock 与后端定义严格一致。
  it('matches every section DTO on both loading and submission', () => {
    const dto = makeSettingsDto()
    const draft = settingsSectionsToDraft(dto)
    const update = assembleSettingsUpdate(draft, dto.version)
    for (const [section, type] of [
      ['tool', 'Tool'],
      ['aiRuntime', 'AiRuntime'],
      ['environment', 'Environment'],
      ['backendNetwork', 'BackendNetwork'],
      ['storageMedia', 'StorageMedia'],
      ['advanced', 'Advanced'],
    ] as const) {
      const source = backendSource(
        `platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings/dto/SystemSettings${type}DTO.java`,
      )
      // 顶层字段声明以两个空格缩进；嵌套 SystemSettingsToolPermissionRuleDTO 等内部类型不属于 section 字段。
      const fields = [...source.matchAll(/^ {2}private [^\n;]+ (\w+);$/gm)]
        .map((match) => match[1])
        .sort()
      expect(fields.length).toBeGreaterThan(0)
      expect(Object.keys(dto[section]).sort(), section).toEqual(fields)
      expect(Object.keys(update[section]).sort(), section).toEqual(fields)
    }
  })

  // 服务端 schema 路径必须与前端 mock 和运行时草稿分别一致，不能只让 mock 自洽。
  it('matches the server schema field paths without weakening validation', () => {
    const settingsPackage = 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings'
    const provider = backendSource(`${settingsPackage}/SystemSettingsSchemaProvider.java`)
    // 只读取组合根实际注册的 section，避免扫描到未接线的定义后让测试假通过。
    const definitions = [...provider.matchAll(/\b(\w+SettingsSchema)\.definition\(/g)]
    expect(definitions).toHaveLength(6)
    const source = definitions
      .map((match) => backendSource(`${settingsPackage}/${match[1]}.java`))
      .join('\n')
    const paths = [...source.matchAll(/\bfield\(\s*"([^"]+)"/g)]
      .map((match) => match[1])
      .sort()
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const fixturePaths = schema.sections.flatMap((section) =>
      section.groups.flatMap((group) => group.fields.map((field) => field.path)),
    ).sort()
    expect(paths.length).toBeGreaterThan(0)
    expect(fixturePaths).toEqual(paths)
    expect(draftLeafPaths(draft).sort()).toEqual(paths)
    expect(validateSystemSettingsSchema(schema, draft)).toBeNull()
  })
})
