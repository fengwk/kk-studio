import { describe, expect, it } from 'vitest'
import {
  makeSettingsDto,
  makeSettingsSchema,
} from '@/test-support/settings-test-fixtures'
import { settingsSectionsToDraft } from '@/features/settings/system-settings-draft'
import { validateSystemSettingsSchema } from '@/features/settings/system-settings-schema-validation'

/**
 * settings schema validation 纯函数契约测试：
 * - fail-closed 枚举每一条「结构与 draft 不匹配」的拒绝路径（精确到返回消息）；
 * - 合法 schema + 合法 draft 必须返回 null（与 schema 整体一致，不依赖渲染组件）；
 * - 从「前一阶段」renderer 测试已覆盖的重复 field path 之外，补齐 sections/groups/options/类型/枚举
 *   与 model selection 等 validateSystemSettingsSchema 尚未被单测触达的分支。
 *
 * 本测试只读 schema 校验入口，不渲染 React 组件；fixture 复用了 settings-test-fixtures 的
 * makeSettingsSchema/makeSettingsDto，保证与 renderer/settings-server-editor 测试同源。
 *
 * 校验器按顺序执行检查（先结构/重复，再类型，最后路径集合），因此若干拒绝路径存在
 * 「必须先满足前序检查才能触达」的约束，相关用例的注释里都写明了原因。
 */
describe('system settings schema validation', () => {
  const draft = settingsSectionsToDraft(makeSettingsDto())

  it('accepts a valid schema as null (fail-open for the editor)', () => {
    // 合法 schema + 完整 draft：路径集合、类型、bounds、options、枚举声明全部通过。
    expect(validateSystemSettingsSchema(makeSettingsSchema(), draft)).toBeNull()
  })

  it('rejects a schema without any sections', () => {
    // schema.sections 为空（缺 section）时，编辑器不应尝试渲染任何 tab。
    expect(validateSystemSettingsSchema({ sections: [] }, draft)).toBe(
      'settings schema has no sections',
    )
  })

  it('rejects a duplicate section key', () => {
    const schema = makeSettingsSchema()
    // 复制整个 aiRuntime section：key 重复必须被 fail-closed，而不是叠加渲染。
    schema.sections.push(structuredClone(schema.sections[0]!))
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      'duplicate settings schema section: aiRuntime',
    )
  })

  it('rejects a duplicate group key', () => {
    const schema = makeSettingsSchema()
    // 在 tool 组内追加一个 key 与 tool.permission 相同的组：重复组必须被拒绝。
    // （不能选 aiRuntime.retry 组：其 ENUM 字段会在重复组检查前先触发类型校验失败，
    // 永远轮不到重复组错误。）
    const tool = schema.sections.find((candidate) => candidate.key === 'tool')!
    tool.groups.push(structuredClone(tool.groups[0]!))
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      'duplicate settings schema group: tool.permission',
    )
  })

  it('rejects a non-array or empty options list', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[0]!.fields[1]!
    // ENUM 字段的 options 必须是数组且非空；先给一个空数组。
    field.options = []
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `invalid settings schema options: ${field.path}`,
    )
    // 再给一个非数组（如字符串）：同样 fail-closed。
    ;(field as { options: unknown }).options = 'FIXED,EXPONENTIAL'
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `invalid settings schema options: ${field.path}`,
    )
  })

  it('rejects duplicate option values', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[0]!.fields[1]!
    // 两个 option 的 value 相同：下拉选项必须是唯一的，重复值会导致歧义选择。
    field.options = [
      { value: 'FIXED', labelKey: 'settings.option.retryBackoff.fixed' },
      { value: 'FIXED', labelKey: 'settings.option.retryBackoff.fixed' },
    ]
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `invalid settings schema option: ${field.path}`,
    )
  })

  it('rejects an option with a blank value or label key', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[0]!.fields[1]!
    // value 为空字符串：无意义选项必须 fail-closed。
    field.options = [{ value: ' ', labelKey: 'settings.option.retryBackoff.fixed' }]
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `invalid settings schema option: ${field.path}`,
    )
    // labelKey 为空字符串：缺少展示文案的选项同样必须被拒绝。
    field.options = [{ value: 'FIXED', labelKey: '' }]
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `invalid settings schema option: ${field.path}`,
    )
  })

  it('rejects an ENUM field without options', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[0]!.fields[1]!
    // ENUM 字段必须声明可选项；options=null 时不能退化成自由文本。
    field.options = null
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `settings schema options are required: ${field.path}`,
    )
  })

  it('rejects a PERMISSION field without options', () => {
    const schema = makeSettingsSchema()
    const section = schema.sections.find((candidate) => candidate.key === 'tool')!
    const field = section.groups[0]!.fields[0]!
    // PERMISSION 字段的 action 枚举同样必须声明；options 为空数组时，
    // 校验顺序先命中「options 非空数组」检查（比 options are required 更早失败）。
    field.options = []
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `invalid settings schema options: ${field.path}`,
    )
    // options=null 时不存在数组检查，才走到「PERMISSION 缺 options」拒绝路径。
    field.options = null
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `settings schema options are required: ${field.path}`,
    )
  })

  it('rejects an ENUM value that is not declared in options', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[0]!.fields[1]!
    // draft 的 EXPONENTIAL 值未在 options 中声明：服务端 schema 与前端 draft 不同步。
    field.options = [{ value: 'FIXED', labelKey: 'settings.option.retryBackoff.fixed' }]
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      `settings schema enum value is not declared: ${field.path}`,
    )
  })

  it('rejects a non-nullable MODEL_SELECTION field with a null draft value', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[1]!.fields[1]!
    // fixture 的 compactionFallbackModel 是 null 且 nullable=true；改成非空后必须失败。
    field.nullable = false
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(
      /settings schema type does not match draft/,
    )
  })

  it('rejects an invalid MODEL_SELECTION draft shape', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[1]!.fields[1]!
    // draft 值不是合法的 {providerName, modelName, variant} 形状（缺 variant）。
    ;(draft.aiRuntime as { compactionFallbackModel: unknown }).compactionFallbackModel = {
      providerName: 'minimax',
      modelName: 'MiniMax',
    }
    field.nullable = true
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(
      /settings schema type does not match draft/,
    )
  })

  it('rejects a duplicate field path', () => {
    const schema = makeSettingsSchema()
    // 复制 retryMaxDelayMillis（LONG，draft 值为 '60000'）两次：第二次命中重复路径检查。
    // （不能复制 MODEL_SELECTION 字段：重复值会在到达重复路径检查前先命中
    // 「nullable 值类型不匹配」，永远轮不到重复路径错误。）
    const retryMaxDelay = schema.sections[0]!.groups[0]!.fields[3]!
    schema.sections[0]!.groups[0]!.fields.push(structuredClone(retryMaxDelay))
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      'duplicate settings schema field: aiRuntime.retryMaxDelayMillis',
    )
  })

  it('rejects an unknown draft path referenced by a schema field', () => {
    const schema = makeSettingsSchema()
    const field = schema.sections[0]!.groups[1]!.fields[0]!
    // schema 引用了 draft 中不存在的路径：getDraftValue 会抛错，校验必须把它转成错误消息。
    field.path = 'aiRuntime.compactionFallbackModel.bogus'
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      'unknown system settings draft path: aiRuntime.compactionFallbackModel.bogus',
    )
  })

  it('rejects a schema field list that misses a draft leaf', () => {
    const schema = makeSettingsSchema()
    // 从 schema 中删掉一个字段：draft 存在但 schema 未声明，路径集合不相等。
    schema.sections[0]!.groups[1]!.fields.splice(1, 1)
    expect(validateSystemSettingsSchema(schema, draft)).toBe(
      'settings schema field paths do not match the editable draft',
    )
  })
})
