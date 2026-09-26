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
  // 对照后端字段事实，避免前端 DTO、草稿和 mock 同时保留旧字段而让测试假通过。
  it('matches every section DTO on both loading and submission', () => {
    const dto = makeSettingsDto()
    const draft = settingsSectionsToDraft(dto)
    const update = assembleSettingsUpdate(draft, dto.version)
    for (const [section, type] of [
      ['tool', 'Tool'],
      ['aiRuntime', 'AiRuntime'],
      ['environment', 'Environment'],
      ['integrations', 'Integrations'],
      ['storageMedia', 'StorageMedia'],
      ['advanced', 'Advanced'],
    ] as const) {
      const source = backendSource(
        `share/src/main/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettings${type}DTO.java`,
      )
      // 当前 DTO 顶层字段声明以两个空格缩进；排除嵌套 PermissionRuleDTO 等内部类型。
      const fields = [...source.matchAll(/^ {2}private [^\n;]+ (\w+);$/gm)].map((match) => match[1]).sort()
      expect(fields.length).toBeGreaterThan(0)
      expect(Object.keys(dto[section]).sort(), section).toEqual(fields)
      expect(Object.keys(update[section]).sort(), section).toEqual(fields)
    }
  })

  // 服务端 schema 路径必须与前端 mock 和运行时草稿分别一致，不能只让 mock 自洽。
  it('matches the server schema field paths without weakening validation', () => {
    const source = backendSource(
      'platform/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsSchemaProvider.java',
    )
    const paths = [...source.matchAll(/\bfield\(\s*"([^"]+)"/g)].map((match) => match[1]).sort()
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
