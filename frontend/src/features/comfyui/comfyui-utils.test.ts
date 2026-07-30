import { describe, expect, it } from 'vitest'
import { buildComfyuiParameters, discoverComfyuiDownloads, filterComfyuiWorkflows, getBindingSummary, initialBindingValues, isComfyuiPollingStatus, isComfyuiTerminalStatus, parseComfyuiBindings, prettyJson, validateComfyuiWorkflowDraft, workflowToDraft } from '@/features/comfyui/comfyui-utils'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts'

describe('comfyui utils', () => {
  it('validates editor JSON and normalizes workflow payloads', () => {
    expect(
      validateComfyuiWorkflowDraft({
        apiName: ' image-api ',
        name: ' Image API ',
        description: ' ',
        workflowJson: ' {"1":{}} ',
        inputBindingsJson: ' [] ',
        defaultSelector: ' $.outputs ',
        enabled: true,
      }),
    ).toEqual({
      apiName: 'image-api',
      name: 'Image API',
      description: null,
      workflowJson: '{"1":{}}',
      inputBindingsJson: '[]',
      defaultSelector: '$.outputs',
      enabled: true,
    })
    expect(() => validateComfyuiWorkflowDraft(draft({ apiName: 'Bad_Name' }))).toThrow('API name')
    expect(() => validateComfyuiWorkflowDraft(draft({ name: '' }))).toThrow('Display name')
    expect(() => validateComfyuiWorkflowDraft(draft({ workflowJson: '' }))).toThrow('Workflow JSON 不能为空')
    expect(() => validateComfyuiWorkflowDraft(draft({ workflowJson: '[]' }))).toThrow('JSON 对象')
    expect(() => validateComfyuiWorkflowDraft(draft({ inputBindingsJson: '{}' }))).toThrow('必须是数组')
    expect(() => validateComfyuiWorkflowDraft(draft({ inputBindingsJson: '[broken' }))).toThrow('不是合法 JSON')
  })

  it('parses binding schemas, initializes defaults, and builds typed parameters', () => {
    const bindings = parseComfyuiBindings(
      JSON.stringify([
        { name: 'title', kind: 'parameter', nodeId: '1', inputName: 'title', required: true, defaultValue: 'hello' },
        { name: 'count', kind: 'parameter', nodeId: '1', inputName: 'count', valueType: 'integer', defaultValue: 2 },
        { name: 'ratio', kind: 'parameter', nodeId: '1', inputName: 'ratio', valueType: 'number' },
        { name: 'enabled', kind: 'parameter', nodeId: '1', inputName: 'enabled', valueType: 'boolean', defaultValue: false },
        { name: 'optionalFlag', kind: 'parameter', nodeId: '1', inputName: 'optionalFlag', valueType: 'boolean' },
        { name: 'requiredFlag', kind: 'parameter', nodeId: '1', inputName: 'requiredFlag', valueType: 'boolean', required: true },
        { name: 'config', kind: 'parameter', nodeId: '1', inputName: 'config', valueType: 'json', defaultValue: { seed: 1 } },
        { name: 'image', kind: 'file', nodeId: '2', inputName: 'image' },
      ]),
    )
    expect(initialBindingValues(bindings)).toEqual({
      title: 'hello',
      count: '2',
      ratio: '',
      enabled: 'false',
      optionalFlag: '',
      requiredFlag: '',
      config: '{\n  "seed": 1\n}',
    })
    expect(
      buildComfyuiParameters(bindings, {
        title: 'demo',
        count: '-3',
        ratio: '1.5',
        enabled: 'false',
        optionalFlag: '',
        requiredFlag: 'true',
        config: '{"seed":9}',
      }),
    ).toEqual({ title: 'demo', count: -3, ratio: 1.5, enabled: false, requiredFlag: true, config: { seed: 9 } })
    expect(() => buildComfyuiParameters(bindings, { title: '', count: 'x', enabled: 'false', requiredFlag: 'true', config: '{}' })).toThrow('title 为必填项')
    expect(() => buildComfyuiParameters(bindings, { title: 'x', count: '1.2', enabled: 'false', requiredFlag: 'true', config: '{}' })).toThrow('必须是整数')
    expect(() => buildComfyuiParameters(bindings, { title: 'x', count: '2', ratio: 'NaN', enabled: 'false', requiredFlag: 'true', config: '{}' })).toThrow('必须是数字')
    expect(() => buildComfyuiParameters(bindings, { title: 'x', count: '2', enabled: 'false', requiredFlag: 'true', config: '{' })).toThrow('参数 config')
    expect(() => buildComfyuiParameters(bindings, { title: 'x', count: '2', enabled: 'false', requiredFlag: '', config: '{}' })).toThrow(
      '必须明确选择 true 或 false',
    )
  })

  it('infers omitted value types from non-null defaults for controls and typed submission', () => {
    const bindings = parseComfyuiBindings(
      JSON.stringify([
        { name: 'flag', kind: 'parameter', nodeId: '1', inputName: 'flag', defaultValue: false },
        { name: 'count', kind: 'parameter', nodeId: '1', inputName: 'count', defaultValue: 4 },
        { name: 'ratio', kind: 'parameter', nodeId: '1', inputName: 'ratio', defaultValue: 1.5 },
        { name: 'title', kind: 'parameter', nodeId: '1', inputName: 'title', defaultValue: 'hello' },
        { name: 'config', kind: 'parameter', nodeId: '1', inputName: 'config', defaultValue: { seed: 1 } },
        { name: 'choices', kind: 'parameter', nodeId: '1', inputName: 'choices', defaultValue: ['a', 'b'] },
        { name: 'nullable', kind: 'parameter', nodeId: '1', inputName: 'nullable', defaultValue: null },
        { name: 'unset', kind: 'parameter', nodeId: '1', inputName: 'unset' },
      ]),
    )

    expect(bindings.map((binding) => binding.valueType)).toEqual([
      'boolean',
      'integer',
      'number',
      'string',
      'json',
      'json',
      'string',
      'string',
    ])
    const values = initialBindingValues(bindings)
    expect(values).toEqual({
      flag: 'false',
      count: '4',
      ratio: '1.5',
      title: 'hello',
      config: '{\n  "seed": 1\n}',
      choices: '[\n  "a",\n  "b"\n]',
      nullable: '',
      unset: '',
    })
    expect(buildComfyuiParameters(bindings, values)).toEqual({
      flag: false,
      count: 4,
      ratio: 1.5,
      title: 'hello',
      config: { seed: 1 },
      choices: ['a', 'b'],
    })
  })

  it('reports malformed binding details instead of throwing through cards', () => {
    expect(getBindingSummary('[]')).toEqual({ count: 0, error: null })
    expect(getBindingSummary('{}').error).toContain('必须是数组')
    expect(() => parseComfyuiBindings('[null]')).toThrow('必须是 JSON 对象')
    expect(() => parseComfyuiBindings('[{"name":"x","kind":"unknown","nodeId":"1","inputName":"x"}]')).toThrow('parameter 或 file')
    expect(() =>
      parseComfyuiBindings('[{"name":"x","kind":"parameter","nodeId":"1","inputName":"x","required":"yes"}]'),
    ).toThrow('required 必须是布尔值')
    expect(() =>
      parseComfyuiBindings('[{"name":"x","kind":"file","nodeId":"1","inputName":"x","defaultValue":"bad"}]'),
    ).toThrow('不能设置')
    expect(() =>
      parseComfyuiBindings('[{"name":"x","kind":"parameter","nodeId":"1","inputName":"x","valueType":"date"}]'),
    ).toThrow('valueType')
    expect(() =>
      parseComfyuiBindings('[{"name":"x","kind":"parameter","nodeId":"1","inputName":"x","valueType":"boolean","defaultValue":"false"}]'),
    ).toThrow('必须是布尔值')
    expect(() =>
      parseComfyuiBindings('[{"name":"x","kind":"file","nodeId":"1","inputName":"x"},{"name":"x","kind":"file","nodeId":"2","inputName":"x"}]'),
    ).toThrow('重复')
  })

  it('filters workflows, maps drafts, discovers nested unique downloads, and classifies statuses', () => {
    const item = workflow()
    expect(filterComfyuiWorkflows([item], 'image')).toEqual([item])
    expect(filterComfyuiWorkflows([item], 'missing')).toEqual([])
    expect(workflowToDraft(item)).toEqual({
      apiName: 'image-api',
      name: 'Image API',
      description: '',
      workflowJson: '{}',
      inputBindingsJson: '[]',
      defaultSelector: '',
      enabled: true,
    })
    expect(
      discoverComfyuiDownloads({
        outputs: [
          { downloadUrl: '/api/a', filename: 'a.png' },
          { nested: { downloadUrl: '/api/b', name: 'b.png' } },
          { downloadUrl: '/api/a', filename: 'duplicate.png' },
        ],
      }),
    ).toEqual([
      { downloadUrl: '/api/a', filename: 'a.png' },
      { downloadUrl: '/api/b', filename: 'b.png' },
    ])
    expect(isComfyuiPollingStatus(' PENDING ')).toBe(true)
    expect(isComfyuiPollingStatus('completed')).toBe(false)
    expect(isComfyuiTerminalStatus('COMPLETE')).toBe(true)
    expect(isComfyuiTerminalStatus('interrupted')).toBe(true)
    expect(isComfyuiTerminalStatus('failed')).toBe(true)
    expect(isComfyuiTerminalStatus('running')).toBe(false)
    expect(isComfyuiTerminalStatus('vendor_waiting')).toBe(false)
    expect(prettyJson(undefined)).toBe('-')
    expect(prettyJson({ ok: true })).toContain('"ok": true')
  })
})

function draft(overrides: Partial<Parameters<typeof validateComfyuiWorkflowDraft>[0]>) {
  return {
    apiName: 'image-api',
    name: 'Image API',
    description: '',
    workflowJson: '{}',
    inputBindingsJson: '[]',
    defaultSelector: '',
    enabled: true,
    ...overrides,
  }
}

function workflow(): ComfyuiWorkflowApiDTO {
  return {
    id: 'workflow-1',
    apiName: 'image-api',
    name: 'Image API',
    description: null,
    workflowJson: '{}',
    inputBindingsJson: '',
    defaultSelector: null,
    enabled: true,
    createTime: null,
    updateTime: null,
  }
}
