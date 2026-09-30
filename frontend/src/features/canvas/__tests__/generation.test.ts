import { describe, expect, it } from 'vitest'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import {
  canAddReference,
  configToFunctionArgs,
  createDefaultFunctionConfig,
  extractParametersFromDefinition,
  filterConfigReferences,
  functionSupportsReferences,
  parseFunctionConfig,
  referenceAlias,
  referenceCandidates,
  referenceKey,
} from '@/features/canvas/generation'
import type { CanvasFunctionDefinitionDTO, UUIDString } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString

const NODE_SINGLE = '00000000-0000-4000-8000-000000000002' as UUIDString
const NODE_MULTI = '00000000-0000-4000-8000-000000000003' as UUIDString
const NODE_OUTPUT = '00000000-0000-4000-8000-000000000004' as UUIDString
const NODE_VIDEO = '00000000-0000-4000-8000-000000000005' as UUIDString
const NODE_TARGET = '00000000-0000-4000-8000-000000000009' as UUIDString

const imageModel: CanvasFunctionDefinitionDTO = {
  name: 'fake-image',
  description: 'Fake Image',
  outputs: [{ kind: 'IMAGE', name: null }],
  argsSchema: {
    type: 'object',
    required: ['count'],
    properties: {
      prompt: {
        type: 'string',
        description: 'Prompt text',
      },
      ratio: {
        type: 'string',
        title: '比例',
        default: 'AUTO',
        enum: ['AUTO', '16:9'],
      },
      count: {
        type: 'integer',
        title: '数量',
        default: 1,
        minimum: 1,
        maximum: 4,
      },
      references: {
        type: 'array',
        items: { type: 'resourceReference' },
      },
    },
  },
  referencePolicy: {
    allowedKinds: ['IMAGE'],
    maxReferences: 2,
    maxByKind: { IMAGE: 2 },
  },
  available: true,
  unavailableReason: null,
}

const reportModelNoRef: CanvasFunctionDefinitionDTO = {
  name: 'fake-report',
  description: 'Fake Report',
  outputs: [{ kind: 'TEXT', name: 'report.txt' }],
  argsSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      prompt: {
        type: 'string',
      },
    },
  },
  referencePolicy: {
    allowedKinds: ['IMAGE'],
    maxReferences: 12,
  },
  available: true,
  unavailableReason: null,
}

function node(
  id: UUIDString,
  name: string,
  kinds: Array<'IMAGE' | 'VIDEO'>,
  functionNode = false,
): ResourceNode {
  return {
    id,
    canvasId: CANVAS_ID,
    name,
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: kinds.map((kind, index) => ({
      id: `${id}-r${index}` as UUIDString,
      canvasId: CANVAS_ID,
      ownerNodeId: id,
      resourceIndex: index,
      blobId: 'blob-output',
      name: `${name}-${index}`,
      textContent: null,
      kind,
      mediaType: kind === 'IMAGE' ? 'image/png' : 'video/mp4',
      sizeBytes: 3,
      width: null,
      height: null,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    })),
    function: functionNode ? { name: 'fake-image', args: { prompt: '', ratio: 'AUTO' } } : null,
    run: null,
  }
}

function snapshot(): CanvasSnapshot {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    resourceNodes: [
      node(NODE_SINGLE, 'single', ['IMAGE']),
      node(NODE_MULTI, 'multi', ['IMAGE', 'IMAGE']),
      node(NODE_OUTPUT, 'function-output', ['IMAGE'], true),
      node(NODE_VIDEO, 'video', ['VIDEO']),
      node(NODE_TARGET, 'target', [], true),
    ],
    groups: [],
    references: [],
  }
}

describe('Canvas canonical generation codec & policy', () => {
  it('builds descriptor defaults and parses canonical flat args losslessly without invalid=>default overwrite', () => {
    // 默认配置断言：prompt 为纯文本，references 为独立数组，业务参数直接扁平补齐默认值
    expect(createDefaultFunctionConfig(imageModel)).toEqual({
      prompt: '',
      references: [],
      parameters: { ratio: 'AUTO', count: 1 },
    })

    // 解析扁平 args，不丢弃未来字段（如 customSetting），补全缺省参数（如 count: 1）
    const parsed = parseFunctionConfig({
      prompt: 'raw prompt text with @mention and <tags>',
      ratio: '16:9',
      customSetting: 42,
      references: [{ type: 'resource', nodeId: NODE_SINGLE, index: 0 }],
    }, imageModel)

    expect(parsed.prompt).toBe('raw prompt text with @mention and <tags>')
    expect(parsed.parameters).toEqual({ ratio: '16:9', count: 1, customSetting: 42 })
    expect(parsed.references).toEqual([{ type: 'resource', nodeId: NODE_SINGLE, index: 0 }])

    // 关键事实：已有的非常规/越界值绝不能被擅自覆盖为 schema 默认值（保证用户编辑不被隐式重置）
    const withCustomValues = parseFunctionConfig({
      prompt: 'test',
      ratio: 'CUSTOM_RATIO', // 不在 enum 中
      count: 99, // 超过 maximum: 4
      references: [],
    }, imageModel)
    expect(withCustomValues.parameters.ratio).toBe('CUSTOM_RATIO')
    expect(withCustomValues.parameters.count).toBe(99)

    // 空输入安全返回默认配置
    expect(parseFunctionConfig(null, imageModel)).toEqual(createDefaultFunctionConfig(imageModel))
  })

  it('supports decimals (float number), numeric enums, and nested future fields losslessly', () => {
    // 1. 小数支持
    const floatModel: CanvasFunctionDefinitionDTO = {
      name: 'model-float',
      description: 'Float Model',
      outputs: [{ kind: 'IMAGE', name: null }],
      argsSchema: {
        type: 'object',
        properties: {
          scale: { type: 'number', title: '缩放', minimum: 0.1, maximum: 2.0, default: 1.0 },
        },
      },
      available: true,
      unavailableReason: null,
    }
    const floatParamDefs = extractParametersFromDefinition(floatModel)
    expect(floatParamDefs[0]?.type).toBe('NUMBER')
    expect(floatParamDefs[0]?.isInteger).toBe(false)

    const parsedFloat = parseFunctionConfig({ scale: 0.75 }, floatModel)
    expect(parsedFloat.parameters.scale).toBe(0.75)
    expect(configToFunctionArgs(parsedFloat, floatModel)).toEqual({ scale: 0.75 })

    // 2. Numeric Enum 保持原始 JSON 类型（不转成 string）
    const numEnumModel: CanvasFunctionDefinitionDTO = {
      name: 'model-num-enum',
      description: 'Numeric Enum Model',
      outputs: [{ kind: 'IMAGE', name: null }],
      argsSchema: {
        type: 'object',
        properties: {
          steps: { type: 'integer', enum: [10, 20, 30], default: 20 },
        },
      },
      available: true,
      unavailableReason: null,
    }
    const enumParamDefs = extractParametersFromDefinition(numEnumModel)
    expect(enumParamDefs[0]?.type).toBe('ENUM')
    expect(enumParamDefs[0]?.options).toEqual([10, 20, 30])
    const parsedNumEnum = parseFunctionConfig({ steps: 30 }, numEnumModel)
    expect(parsedNumEnum.parameters.steps).toBe(30)
    expect(typeof parsedNumEnum.parameters.steps).toBe('number')
    expect(configToFunctionArgs(parsedNumEnum, numEnumModel)).toEqual({ steps: 30 })

    // 3. 未来合法嵌套字段及数组参数完整保留
    const complexNestedArgs = {
      prompt: 'cinema',
      nested: { deep: { value: 123 }, list: [1, 2, 3] },
      futureFlags: ['a', 'b'],
    }
    const parsedNested = parseFunctionConfig(complexNestedArgs, imageModel)
    expect(parsedNested.parameters.nested).toEqual({ deep: { value: 123 }, list: [1, 2, 3] })
    expect(parsedNested.parameters.futureFlags).toEqual(['a', 'b'])
    const wireBack = configToFunctionArgs(parsedNested, imageModel)
    expect(wireBack.nested).toEqual({ deep: { value: 123 }, list: [1, 2, 3] })
    expect(wireBack.futureFlags).toEqual(['a', 'b'])
  })

  it('routes unsupported prompt shapes and malformed references to rawArgs without silent data loss', () => {
    // 1. prompt 为对象结构（如旧版 prompt.segments 或自定义 prompt 对象）：不能静默置空，转入 rawArgs
    const objectPromptArgs = {
      prompt: { segments: [{ type: 'TEXT', text: 'keep me' }] },
      ratio: '16:9',
    }
    const parsedObjPrompt = parseFunctionConfig(objectPromptArgs, imageModel)
    expect(parsedObjPrompt.rawArgs).toEqual(objectPromptArgs)
    expect(parsedObjPrompt.rawError).toBeDefined()
    // configToFunctionArgs 必须直接无损回传原始对象，绝不丢失 prompt 数据
    expect(configToFunctionArgs(parsedObjPrompt, imageModel)).toEqual(objectPromptArgs)

    // 2. references 包含非法形状（如非 UUID 数字 ID、非数组）：不能静默剔除，转入 rawArgs
    const malformedRefArgs = {
      prompt: 'valid prompt',
      references: [
        { type: 'resource', nodeId: '12345', index: 0 },
      ],
    }
    const parsedMalformedRef = parseFunctionConfig(malformedRefArgs, imageModel)
    expect(parsedMalformedRef.rawArgs).toEqual(malformedRefArgs)
    expect(parsedMalformedRef.rawError).toBeDefined()
    expect(configToFunctionArgs(parsedMalformedRef, imageModel)).toEqual(malformedRefArgs)

    // 3. schema 定义中 prompt 并非 string（例如 prompt 为 object）：转入 rawArgs 模式
    const objectPromptSchemaModel: CanvasFunctionDefinitionDTO = {
      name: 'model-complex-prompt',
      description: 'Complex Prompt Model',
      outputs: [{ kind: 'IMAGE', name: null }],
      argsSchema: {
        type: 'object',
        properties: {
          prompt: { type: 'object' },
        },
      },
      available: true,
      unavailableReason: null,
    }
    const parsedComplexSchema = parseFunctionConfig({ prompt: { text: 'hi' } }, objectPromptSchemaModel)
    expect(parsedComplexSchema.rawArgs).toBeDefined()
    expect(parsedComplexSchema.rawError).toBeDefined()
  })

  it('strictly validates functionSupportsReferences against schema properties and never injects references if undeclared', () => {
    // 严格按声明 properties.references 为 array items resourceReference 判定
    expect(functionSupportsReferences(imageModel)).toBe(true)
    expect(functionSupportsReferences(reportModelNoRef)).toBe(false)
    expect(functionSupportsReferences(null)).toBe(false)

    // 即使 referencePolicy 设置了 allowedKinds，但 argsSchema 中未声明 references 属性，绝不推断支持
    const policyOnlyModel: CanvasFunctionDefinitionDTO = {
      name: 'model-policy-only',
      description: 'Policy Only',
      outputs: [{ kind: 'IMAGE', name: null }],
      argsSchema: {
        type: 'object',
        properties: { prompt: { type: 'string' } },
      },
      referencePolicy: {
        allowedKinds: ['IMAGE'],
        maxReferences: 5,
      },
      available: true,
      unavailableReason: null,
    }
    expect(functionSupportsReferences(policyOnlyModel)).toBe(false)

    // 对不支持引用的模型，configToFunctionArgs 绝不向 wire 注入 references 属性
    const wire = configToFunctionArgs({ prompt: 'test', references: [], parameters: {} }, policyOnlyModel)
    expect(wire).not.toHaveProperty('references')
  })

  it('derives referenceCandidates from all non-self resource nodes without requiring prior links', () => {
    const candidates = referenceCandidates(snapshot(), NODE_TARGET, imageModel)
    expect(candidates.map((c) => c.label)).toEqual([
      '@single_0',
      '@multi_0',
      '@multi_1',
      '@function-output_0',
    ])
    // VIDEO 类型由于不符合 imageModel.referencePolicy.allowedKinds(['IMAGE'])，被完全过滤
    expect(candidates.some((c) => c.resource.kind === 'VIDEO')).toBe(false)
    expect(referenceAlias(node(NODE_OUTPUT, 'fn', ['IMAGE'], true), 0)).toBe('@fn_0')
    expect(referenceKey(NODE_SINGLE, 0)).toBe(`${NODE_SINGLE}:0`)

    // 对不支持 references 字段的模型返回空候选
    expect(referenceCandidates(snapshot(), NODE_TARGET, reportModelNoRef)).toEqual([])
  })

  it('enforces total and per-kind reference limits with deduplication', () => {
    const candidates = referenceCandidates(snapshot(), NODE_TARGET, imageModel)
    const first = candidates[0]!
    const second = candidates[1]!
    const third = candidates[2]!

    const currentRefs = [{ type: 'resource' as const, nodeId: first.nodeId, index: first.index }]

    // 允许添加另一个不同的候选
    expect(canAddReference(currentRefs, second, candidates, imageModel)).toBe(true)

    // 重复添加同一个候选被拒绝（去重）
    expect(canAddReference(currentRefs, first, candidates, imageModel)).toBe(false)

    // 达到 maxReferences=1 上限时拒绝
    const oneLimitModel: CanvasFunctionDefinitionDTO = {
      ...imageModel,
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1 },
    }
    expect(canAddReference(currentRefs, second, candidates, oneLimitModel)).toBe(false)

    // 达到单类型 maxByKind 上限时拒绝
    const kindLimitModel: CanvasFunctionDefinitionDTO = {
      ...imageModel,
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 3, maxByKind: { IMAGE: 1 } },
    }
    expect(canAddReference(currentRefs, second, candidates, kindLimitModel)).toBe(false)

    // filterConfigReferences 自动清理过期或超额引用
    const filtered = filterConfigReferences({
      prompt: 'demo',
      references: [
        { type: 'resource', nodeId: first.nodeId, index: first.index },
        { type: 'resource', nodeId: second.nodeId, index: second.index },
        { type: 'resource', nodeId: third.nodeId, index: third.index },
      ],
      parameters: {},
    }, candidates, imageModel)
    expect(filtered.references).toHaveLength(2)
  })

  it('validates actual backend adapter schemas (FakeImage, TextToVideo, FakeReport, MiniMaxH3, Seedance, GptImage2)', () => {
    // 验证真实后端 Java Adapter schemas 的解析与 wire 生成符合契约：
    // 1. FakeImage (IMAGE_ARGS_SCHEMA)
    const fakeImageModel: CanvasFunctionDefinitionDTO = {
      name: 'fake-image',
      description: 'Fake Image',
      outputs: [{ kind: 'IMAGE', name: null }],
      argsSchema: {
        type: 'object',
        properties: {
          prompt: { type: 'string' },
          ratio: { type: 'string', enum: ['AUTO', '1:1', '16:9', '9:16', '4:3', '3:4'], default: 'AUTO' },
          references: { type: 'array', items: { type: 'resourceReference' } },
        },
      },
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 3 },
      available: true,
      unavailableReason: null,
    }
    expect(functionSupportsReferences(fakeImageModel)).toBe(true)
    const defaultFakeImg = createDefaultFunctionConfig(fakeImageModel)
    expect(defaultFakeImg.parameters.ratio).toBe('AUTO')
    const fakeImgWire = configToFunctionArgs({
      prompt: 'a cat',
      references: [{ type: 'resource', nodeId: NODE_SINGLE, index: 0 }],
      parameters: { ratio: '16:9' },
    }, fakeImageModel)
    expect(fakeImgWire).toEqual({
      prompt: 'a cat',
      ratio: '16:9',
      references: [{ type: 'resource', nodeId: NODE_SINGLE, index: 0 }],
    })

    // 2. MiniMaxH3 (MiniMaxH3CanvasFunctionAdapter)
    const miniMaxH3Model: CanvasFunctionDefinitionDTO = {
      name: 'minimax-h3',
      description: 'MiniMax H3 Video',
      outputs: [{ kind: 'VIDEO', name: null }],
      argsSchema: {
        type: 'object',
        properties: {
          prompt: { type: 'string' },
          ratio: { type: 'string', enum: ['16:9', '9:16', '1:1'], default: '16:9' },
          duration: { type: 'integer', minimum: 5, maximum: 10, default: 5 },
          resolution: { type: 'string', enum: ['720p', '1080p'], default: '720p' },
          references: { type: 'array', items: { type: 'resourceReference' } },
        },
      },
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1 },
      available: true,
      unavailableReason: null,
    }
    expect(functionSupportsReferences(miniMaxH3Model)).toBe(true)
    const miniMaxDefaults = createDefaultFunctionConfig(miniMaxH3Model)
    expect(miniMaxDefaults.parameters).toEqual({
      ratio: '16:9',
      duration: 5,
      resolution: '720p',
    })
    const miniMaxWire = configToFunctionArgs({
      prompt: 'a running dog',
      references: [{ type: 'resource', nodeId: NODE_SINGLE, index: 0 }],
      parameters: { ratio: '9:16', duration: 10, resolution: '1080p' },
    }, miniMaxH3Model)
    expect(miniMaxWire).toEqual({
      prompt: 'a running dog',
      ratio: '9:16',
      duration: 10,
      resolution: '1080p',
      references: [{ type: 'resource', nodeId: NODE_SINGLE, index: 0 }],
    })

    // 3. FakeReport (REPORT_ARGS_SCHEMA): additionalProperties: false, 无 references
    const fakeReportModel: CanvasFunctionDefinitionDTO = {
      name: 'fake-report',
      description: 'Fake Report',
      outputs: [{ kind: 'TEXT', name: 'report.txt' }],
      argsSchema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          prompt: { type: 'string' },
        },
      },
      available: true,
      unavailableReason: null,
    }
    expect(functionSupportsReferences(fakeReportModel)).toBe(false)
    const reportWire = configToFunctionArgs({
      prompt: 'generate summary',
      references: [],
      parameters: {},
    }, fakeReportModel)
    expect(reportWire).toEqual({ prompt: 'generate summary' })
    expect(reportWire).not.toHaveProperty('references')
  })
})
