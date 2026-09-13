import { describe, expect, it } from 'vitest'
import {
  assertNoDuplicateJsonKeys,
  createLocalConfigTemplate,
  extractDraftConnectionType,
  extractDraftEnvironmentId,
  formatMcpConfigJson,
  isSemanticConfigEqual,
  isTargetAbsoluteCwd,
  REMOTE_CONFIG_TEMPLATE,
  updateLocalEnvironmentIdInJson,
  validateMcpConfigJson,
} from './mcp-config-json'

describe('mcp-config-json', () => {
  describe('assertNoDuplicateJsonKeys', () => {
    /**
     * 测试意图：验证普通重复键被严格拒绝。
     */
    it('rejects simple duplicate keys at root level', () => {
      expect(() => assertNoDuplicateJsonKeys('{"a": 1, "a": 2}')).toThrow(
        /Duplicate key "a" in JSON object/,
      )
    })

    /**
     * 测试意图：验证嵌套对象内部的重复键被检测并拒绝。
     */
    it('rejects duplicate keys inside nested objects', () => {
      expect(() => assertNoDuplicateJsonKeys('{"nested": {"foo": 1, "foo": 2}}')).toThrow(
        /Duplicate key "foo" in JSON object/,
      )
    })

    /**
     * 测试意图：验证转义等效键（Unicode 转义 \\u0061 与字符 a）被识别为同一键并拒绝。
     */
    it('rejects escaped-equivalent duplicate keys like \\u0061 and a', () => {
      expect(() => assertNoDuplicateJsonKeys('{"\\u0061": 1, "a": 2}')).toThrow(
        /Duplicate key "a" in JSON object/,
      )
      expect(() =>
        assertNoDuplicateJsonKeys('{"\\u0075\\u0072\\u006c": "x", "url": "y"}'),
      ).toThrow(/Duplicate key "url" in JSON object/)
    })

    /**
     * 测试意图：验证转义引号等效键（\\" 与 \\u0022）被识别为重复键。
     */
    it('rejects escaped quote equivalent keys', () => {
      expect(() => assertNoDuplicateJsonKeys('{"a\\"b": 1, "a\\u0022b": 2}')).toThrow(
        /Duplicate key "a"b" in JSON object/,
      )
    })

    /**
     * 测试意图：验证不同对象作用域中同名键合法通过。
     */
    it('allows identical keys in different object scopes', () => {
      expect(() =>
        assertNoDuplicateJsonKeys('{"one": {"x": 1}, "two": {"x": 2}}'),
      ).not.toThrow()
      expect(() =>
        assertNoDuplicateJsonKeys('[{"id": "a"}, {"id": "b"}]'),
      ).not.toThrow()
    })

    /**
     * 测试意图：验证尾随多余字符与尾随逗号被严格拒绝。
     */
    it('rejects trailing characters and trailing commas', () => {
      expect(() => assertNoDuplicateJsonKeys('{"a": 1} trailing')).toThrow(
        /Unexpected trailing characters/,
      )
      expect(() => assertNoDuplicateJsonKeys('{"a": 1,}')).toThrow(/Trailing comma/)
      expect(() => assertNoDuplicateJsonKeys('[1, 2,]')).toThrow(/Trailing comma/)
    })

    /**
     * 测试意图：验证空或空白文本抛出异常。
     */
    it('rejects blank JSON text', () => {
      expect(() => assertNoDuplicateJsonKeys('')).toThrow(/must not be blank/)
      expect(() => assertNoDuplicateJsonKeys('   ')).toThrow(/must not be blank/)
    })
  })

  describe('formatMcpConfigJson', () => {
    /**
     * 测试意图：验证格式化合法 JSON 正常展开为多行格式。
     */
    it('formats valid JSON cleanly', () => {
      const formatted = formatMcpConfigJson('{"type":"remote","url":"https://example.com"}')
      expect(formatted).toBe(
        '{\n  "type": "remote",\n  "url": "https://example.com"\n}',
      )
    })

    /**
     * 测试意图：验证存在重复键时格式化主动拒绝，绝不静默覆盖丢失键。
     */
    it('refuses to format JSON with duplicate keys', () => {
      expect(() => formatMcpConfigJson('{"url":"a","url":"b"}')).toThrow(
        /Duplicate key "url"/,
      )
    })
  })

  describe('isTargetAbsoluteCwd', () => {
    /**
     * 测试意图：验证 cwd 支持 Unix 绝对路径、Windows 盘符路径、Windows UNC 路径以及整值 ${VAR}。
     */
    it('accepts valid target-OS absolute paths and whole ${VAR}', () => {
      expect(isTargetAbsoluteCwd('/workspace/project')).toBe(true)
      expect(isTargetAbsoluteCwd('C:/workspace/project')).toBe(true)
      expect(isTargetAbsoluteCwd('D:\\workspace\\project')).toBe(true)
      expect(isTargetAbsoluteCwd('\\\\server\\share\\project')).toBe(true)
      expect(isTargetAbsoluteCwd('${PROJECT_CWD}')).toBe(true)
    })

    /**
     * 测试意图：验证 cwd 拒绝相对路径与内嵌变量（如 /home/${USER}）。
     */
    it('rejects relative paths and embedded variables', () => {
      expect(isTargetAbsoluteCwd('workspace/project')).toBe(false)
      expect(isTargetAbsoluteCwd('./workspace')).toBe(false)
      expect(isTargetAbsoluteCwd('/workspace/${USER}/project')).toBe(false)
      expect(isTargetAbsoluteCwd('C:\\$PROJECT\\dir')).toBe(false)
      expect(isTargetAbsoluteCwd('%PROJECT_DIR%\\sub')).toBe(false)
    })
  })

  describe('validateMcpConfigJson', () => {
    /**
     * 测试意图：验证有效的 Remote 模板通过校验。
     */
    it('accepts valid remote config template', () => {
      const res = validateMcpConfigJson(REMOTE_CONFIG_TEMPLATE)
      expect(res.valid).toBe(true)
      expect(res.error).toBeUndefined()
    })

    /**
     * 测试意图：验证有效的 Local 模板通过校验。
     */
    it('accepts valid local config template', () => {
      const local = createLocalConfigTemplate('00000000-0000-4000-8000-000000000001')
      const res = validateMcpConfigJson(local)
      expect(res.valid).toBe(true)
      expect(res.error).toBeUndefined()
    })

    /**
     * 测试意图：验证 Remote 格式拒绝包含 userinfo 或 fragment 的 URL。
     */
    it('rejects remote URL with credentials or fragment', () => {
      const withCreds = JSON.stringify({
        type: 'remote',
        url: 'https://user:pass@example.com/mcp',
      })
      expect(validateMcpConfigJson(withCreds).error).toContain('user-info credentials')

      const withHash = JSON.stringify({
        type: 'remote',
        url: 'https://example.com/mcp#section',
      })
      expect(validateMcpConfigJson(withHash).error).toContain('fragment')
    })

    /**
     * 测试意图：验证 Remote 格式拒绝未知字段。
     */
    it('rejects unknown fields for remote config', () => {
      const extra = JSON.stringify({
        type: 'remote',
        url: 'https://example.com/mcp',
        command: ['leak'],
      })
      expect(validateMcpConfigJson(extra).error).toContain('Unexpected fields for remote')
    })

    /**
     * 测试意图：验证 Local 格式要求 canonical 小写 UUID 格式的 environmentId。
     */
    it('validates environmentId canonical uuid format for local config', () => {
      const invalidUuid = JSON.stringify({
        type: 'local',
        environmentId: 'not-a-uuid',
        command: ['ls'],
        cwd: '/opt',
      })
      expect(validateMcpConfigJson(invalidUuid).error).toContain('canonical lowercase dashed UUID')
    })

    /**
     * 测试意图：验证 Local 格式要求 command 为非空非白名单字符串数组。
     */
    it('validates command non-empty array for local config', () => {
      const emptyCmd = JSON.stringify({
        type: 'local',
        environmentId: '00000000-0000-4000-8000-000000000001',
        command: [],
        cwd: '/opt',
      })
      expect(validateMcpConfigJson(emptyCmd).error).toContain('command is required and must be a non-empty array')

      const blankCmd = JSON.stringify({
        type: 'local',
        environmentId: '00000000-0000-4000-8000-000000000001',
        command: ['   '],
        cwd: '/opt',
      })
      expect(validateMcpConfigJson(blankCmd).error).toContain('command elements must be non-blank strings')
    })

    /**
     * 测试意图：验证 headers 和 env 映射中的所有值必须为字符串。
     */
    it('requires string maps for headers and env', () => {
      const invalidHeaders = JSON.stringify({
        type: 'remote',
        url: 'https://example.com/mcp',
        headers: { num: 123 },
      })
      expect(validateMcpConfigJson(invalidHeaders).error).toContain('headers values must be strings')

      const invalidEnv = JSON.stringify({
        type: 'local',
        environmentId: '00000000-0000-4000-8000-000000000001',
        command: ['cmd'],
        cwd: '/opt',
        env: { flag: true },
      })
      expect(validateMcpConfigJson(invalidEnv).error).toContain('env values must be strings')
    })

    /**
     * 测试意图：验证 timeoutMillis 必须为正整数。
     */
    it('validates positive integral timeoutMillis', () => {
      const nonPositive = JSON.stringify({
        type: 'remote',
        url: 'https://example.com/mcp',
        timeoutMillis: -10,
      })
      expect(validateMcpConfigJson(nonPositive).error).toContain('timeoutMillis must be a positive integer')

      const floatTimeout = JSON.stringify({
        type: 'remote',
        url: 'https://example.com/mcp',
        timeoutMillis: 1000.5,
      })
      expect(validateMcpConfigJson(floatTimeout).error).toContain('timeoutMillis must be a positive integer')
    })
  })

  describe('isSemanticConfigEqual', () => {
    /**
     * 测试意图：验证仅空白/缩进差异的 JSON 判定为语义等价。
     */
    it('treats whitespace formatting changes as semantically equal', () => {
      const a = '{\n  "type": "remote",\n  "url": "https://example.com"\n}'
      const b = '{"type":"remote","url":"https://example.com"}'
      expect(isSemanticConfigEqual(a, b)).toBe(true)
    })

    /**
     * 测试意图：验证实际字段值修改被识别为语义不同。
     */
    it('identifies value modifications as semantically different', () => {
      const a = '{"type":"remote","url":"https://example.com/v1"}'
      const b = '{"type":"remote","url":"https://example.com/v2"}'
      expect(isSemanticConfigEqual(a, b)).toBe(false)
    })

    /**
     * 测试意图：含有重复键的 JSON 在语义比对时判定为不等价。
     */
    it('returns false when duplicate keys are present in either payload', () => {
      const a = '{"url":"a","url":"b"}'
      const b = '{"url":"b"}'
      expect(isSemanticConfigEqual(a, b)).toBe(false)
    })
  })

  describe('environmentId rewriting and extractors', () => {
    /**
     * 测试意图：验证在单一权威 JSON 文本内准确替换 environmentId。
     */
    it('rewrites environmentId in JSON text', () => {
      const initial = createLocalConfigTemplate('00000000-0000-0000-0000-000000000001')
      const target = '00000000-0000-4000-8000-000000000099'
      const updated = updateLocalEnvironmentIdInJson(initial, target)
      expect(updated).toContain(target)
      expect(extractDraftEnvironmentId(updated)).toBe(target)
    })

    /**
     * 测试意图：验证连接类型提取器能从有效或草稿文本中识别 remote 与 local。
     */
    it('extracts draft connection type', () => {
      expect(extractDraftConnectionType(REMOTE_CONFIG_TEMPLATE)).toBe('remote')
      expect(extractDraftConnectionType(createLocalConfigTemplate())).toBe('local')
      expect(extractDraftConnectionType('{"type": "local",')).toBe('local')
    })
  })
})
