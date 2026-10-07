import { describe, expect, it } from 'vitest'
import { setLocale, translate } from '@/shared/i18n'
import {
  buildWorkflowDTO,
  createDraftState,
  hasActiveRun,
  MAX_RUNS,
  parseMaxRuns,
  toDraftStates,
  validateWorkflowDraft,
  workflowErrorKey,
  workflowErrorParams,
  type WorkflowDraftState,
  type WorkflowValidationError,
} from './workflow-draft'
import type { ProjectWorkflowDTO } from './types'

function draft(partial: Partial<WorkflowDraftState> & { state: string }): WorkflowDraftState {
  return { ...createDraftState(partial.state), ...partial }
}

/** 满足全部后端约束的图：INIT →(WORK|REVIEW)；WORK →(REVIEW|DONE)；REVIEW(停用) → DONE。 */
function validDraft(): WorkflowDraftState[] {
  return [
    draft({ state: 'INIT', name: '待开始', next: ['WORK', 'REVIEW'] }),
    draft({
      state: 'WORK',
      name: '处理中',
      agent: 'backend-dev',
      environment: 'ubuntu',
      instructions: '实现并自测',
      maxRuns: '3',
      next: ['REVIEW', 'DONE'],
    }),
    draft({ state: 'REVIEW', name: '评审', enabled: false, next: ['DONE'] }),
    draft({ state: 'BLOCKED', name: '业务阻塞' }),
    draft({ state: 'DONE', name: '完成' }),
  ]
}

describe('workflow-draft roundtrip', () => {
  it('preserves multi-next, disabled stages and Agent/Environment/instructions/maxRuns', () => {
    // 测试意图：草稿 → DTO 的往返必须保留多 next 图、停用阶段与 Agent 阶段字段，不压成单 next 流水线。
    const workflow: ProjectWorkflowDTO = {
      states: [
        { state: 'INIT', name: '待开始', enabled: true, next: ['WORK', 'REVIEW'] },
        {
          state: 'WORK',
          name: '处理中',
          agent: 'backend-dev',
          environment: 'ubuntu',
          instructions: '实现并自测',
          maxRuns: '3',
          enabled: true,
          next: ['REVIEW', 'DONE'],
        },
        { state: 'REVIEW', name: '评审', enabled: false, next: ['DONE'] },
        { state: 'BLOCKED', name: '业务阻塞', enabled: true, next: [] },
        { state: 'DONE', name: '完成', enabled: true, next: [] },
      ],
    }

    const dto = buildWorkflowDTO(toDraftStates(workflow))
    expect(dto).toEqual(workflow)
    expect(dto.states[0].next).toEqual(['WORK', 'REVIEW'])
    expect(dto.states[2].enabled).toBe(false)
    expect(dto.states[1]).toMatchObject({
      agent: 'backend-dev',
      environment: 'ubuntu',
      instructions: '实现并自测',
      maxRuns: '3',
    })
  })

  it('omits environment, maxRuns and reserved business fields when not applicable', () => {
    // 测试意图：人工阶段不带 Environment/Run 额度；保留状态不携带任何业务字段。
    const states = [
      draft({ state: 'INIT', name: '待开始', next: ['WORK'] }),
      draft({ state: 'WORK', name: '人工阶段', instructions: '  说明  ', environment: 'ubuntu', maxRuns: '9', next: ['DONE'] }),
      draft({ state: 'BLOCKED', name: '业务阻塞', agent: 'x', instructions: '不应保留' }),
      draft({ state: 'DONE', name: '完成' }),
    ]
    const dto = buildWorkflowDTO(states)
    expect(dto.states[1]).toEqual({
      state: 'WORK',
      name: '人工阶段',
      instructions: '说明',
      enabled: true,
      next: ['DONE'],
    })
    expect(dto.states[2]).toEqual({
      state: 'BLOCKED',
      name: '业务阻塞',
      enabled: true,
      next: [],
    })
  })
})

describe('validateWorkflowDraft', () => {
  it('accepts a workflow that satisfies every backend constraint', () => {
    expect(validateWorkflowDraft(validDraft())).toBeNull()
  })

  it('reports empty workflows, empty codes, invalid codes and duplicate codes', () => {
    expect(validateWorkflowDraft([])).toEqual({ code: 'empty' })
    expect(validateWorkflowDraft([draft({ state: '   ', name: 'x' })])).toEqual({
      code: 'emptyCode',
    })
    expect(validateWorkflowDraft(validDraft().map((state) =>
      state.state === 'WORK' ? { ...state, state: 'work' } : state,
    ))).toEqual({ code: 'invalidCode', state: 'work' })
    const duplicated = validDraft()
    duplicated[1] = { ...duplicated[1], state: 'INIT' }
    expect(validateWorkflowDraft(duplicated)).toEqual({ code: 'duplicateCode', state: 'INIT' })
  })

  it('requires the reserved states, names and referenced stages', () => {
    const missingDone = validDraft().filter((state) => state.state !== 'DONE')
    expect(validateWorkflowDraft(missingDone)).toEqual({ code: 'missingReserved' })

    const blankName = validDraft().map((state) =>
      state.state === 'WORK' ? { ...state, name: '   ' } : state,
    )
    expect(validateWorkflowDraft(blankName)).toEqual({ code: 'blankName', state: 'WORK' })

    expect(validateWorkflowDraft(validDraft(), new Set(['ARCHIVED_STAGE']))).toEqual({
      code: 'referencedStage',
      state: 'ARCHIVED_STAGE',
    })
  })

  it('mirrors reserved-state and manual/Agent field shape rules', () => {
    const reservedAgent = validDraft().map((state) =>
      state.state === 'DONE' ? { ...state, agent: 'dev' } : state,
    )
    expect(validateWorkflowDraft(reservedAgent)).toEqual({
      code: 'reservedBusinessFields',
      state: 'DONE',
    })

    const reservedDisabled = validDraft().map((state) =>
      state.state === 'BLOCKED' ? { ...state, enabled: false } : state,
    )
    expect(validateWorkflowDraft(reservedDisabled)).toEqual({
      code: 'reservedDisabled',
      state: 'BLOCKED',
    })

    const reservedEdge = validDraft().map((state) =>
      state.state === 'DONE' ? { ...state, next: ['INIT'] } : state,
    )
    expect(validateWorkflowDraft(reservedEdge)).toEqual({ code: 'reservedEdge', state: 'DONE' })

    const manualWithEnv = validDraft().map((state) =>
      state.state === 'REVIEW' ? { ...state, environment: 'ubuntu' } : state,
    )
    expect(validateWorkflowDraft(manualWithEnv)).toEqual({ code: 'manualFields', state: 'REVIEW' })
  })

  it('rejects self edges, BLOCKED edges, duplicate edges and undeclared targets', () => {
    const selfEdge = validDraft().map((state) =>
      state.state === 'WORK' ? { ...state, next: ['WORK'] } : state,
    )
    expect(validateWorkflowDraft(selfEdge)).toEqual({ code: 'selfEdge', state: 'WORK' })

    const blockedEdge = validDraft().map((state) =>
      state.state === 'WORK' ? { ...state, next: ['BLOCKED', 'DONE'] } : state,
    )
    expect(validateWorkflowDraft(blockedEdge)).toEqual({ code: 'edgeToBlocked', state: 'WORK' })

    const duplicateEdge = validDraft().map((state) =>
      state.state === 'WORK' ? { ...state, next: ['DONE', 'DONE'] } : state,
    )
    expect(validateWorkflowDraft(duplicateEdge)).toEqual({
      code: 'duplicateEdge',
      state: 'WORK',
      target: 'DONE',
    })

    const undeclared = validDraft().map((state) =>
      state.state === 'WORK' ? { ...state, next: ['GHOST'] } : state,
    )
    expect(validateWorkflowDraft(undeclared)).toEqual({
      code: 'undeclaredTarget',
      state: 'WORK',
      target: 'GHOST',
    })
  })

  it('rejects unreachable enabled stages and missing INIT → DONE path', () => {
    const orphan = validDraft().map((state) => {
      if (state.state === 'INIT') return { ...state, next: ['WORK'] }
      if (state.state === 'WORK') return { ...state, next: ['DONE'] }
      if (state.state === 'REVIEW') return { ...state, enabled: true }
      return state
    })
    expect(validateWorkflowDraft(orphan)).toEqual({ code: 'unreachableStage', state: 'REVIEW' })

    // 所有启用阶段都可达，但没有任何正常路径到达 DONE（含停用阶段在内都不可达）。
    const noPath = validDraft().map((state) => {
      if (state.state === 'INIT') return { ...state, next: ['WORK'] }
      if (state.state === 'WORK') return { ...state, next: ['REVIEW'] }
      if (state.state === 'REVIEW') return { ...state, next: [] }
      return state
    })
    expect(validateWorkflowDraft(noPath)).toEqual({ code: 'noDonePath' })
  })
})

describe('parseMaxRuns', () => {
  it('accepts canonical decimal integers inside the backend int range', () => {
    expect(parseMaxRuns('1')).toBe(1)
    expect(parseMaxRuns(' 42 ')).toBe(42)
    expect(parseMaxRuns(String(MAX_RUNS))).toBe(MAX_RUNS)
  })

  it('rejects zero, negatives, decimals and non-canonical shapes', () => {
    expect(parseMaxRuns('0')).toBeNull()
    expect(parseMaxRuns('-1')).toBeNull()
    expect(parseMaxRuns('3.5')).toBeNull()
    expect(parseMaxRuns('007')).toBeNull()
    expect(parseMaxRuns('1e9')).toBeNull()
    expect(parseMaxRuns('')).toBeNull()
    expect(parseMaxRuns('   ')).toBeNull()
  })

  it('rejects values outside the 32-bit int range or beyond safe precision', () => {
    // 后端 web 层 parseNonNegativeLong 后再限制 int 范围：超出 Integer.MAX_VALUE 必须被拒绝
    expect(parseMaxRuns(String(MAX_RUNS + 1))).toBeNull()
    expect(parseMaxRuns('4294967296')).toBeNull()
    expect(parseMaxRuns('99999999999999999999')).toBeNull()
    expect(parseMaxRuns('9007199254740993')).toBeNull()
  })

  it('is the validator gate for Agent stage run budgets', () => {
    const cases: Array<[string, number | null]> = [
      ['3', 3],
      [String(MAX_RUNS), MAX_RUNS],
      [String(MAX_RUNS + 1), null],
      ['Infinity', null],
      ['NaN', null],
      ['2147483648.5', null],
      ['0', null],
      ['-5', null],
    ]
    for (const [raw, expected] of cases) {
      const states = validDraft().map((state) =>
        state.state === 'WORK' ? { ...state, maxRuns: raw } : state,
      )
      expect(parseMaxRuns(raw), raw).toBe(expected)
      if (expected === null) {
        expect(validateWorkflowDraft(states)).toEqual({
          code: 'agentMaxRuns',
          state: 'WORK',
          max: MAX_RUNS,
        })
      } else {
        expect(validateWorkflowDraft(states)).toBeNull()
      }
    }
  })
})

describe('snapshot derived constraints', () => {
  it('rejects removal of a state referenced only by archived issues or blockedFromState', () => {
    const states = toDraftStates({
      states: [
        { state: 'INIT', name: 'Init', next: ['DONE'] },
        { state: 'BLOCKED', name: 'Blocked' },
        { state: 'DONE', name: 'Done' },
      ],
    })
    expect(validateWorkflowDraft(states, new Set(['ARCHIVED_SOURCE']))).toEqual({
      code: 'referencedStage', state: 'ARCHIVED_SOURCE',
    })
  })

  it('detects active runs only for RUNNING or WAITING statuses', () => {
    expect(hasActiveRun([{ currentOrLatestRun: { status: 'RUNNING' } }])).toBe(true)
    expect(hasActiveRun([{ currentOrLatestRun: { status: 'WAITING' } }])).toBe(true)
    expect(hasActiveRun([{ currentOrLatestRun: { status: 'COMPLETED' } }, { currentOrLatestRun: null }])).toBe(false)
    expect(hasActiveRun(undefined)).toBe(false)
  })
})

/**
 * 结构化校验错误只携带 code + 参数，文案由 catalog 提供：这里保证每个 code 在
 * zh-CN / en-US 下都有真实文案，且占位符都被替换成实际值（不回显 {{...}} 或 ⟦missing:…⟧）。
 */
describe('workflow validation error messages', () => {
  const CODES: Record<WorkflowValidationError['code'], true> = {
    empty: true,
    missingReserved: true,
    noDonePath: true,
    emptyCode: true,
    invalidCode: true,
    duplicateCode: true,
    referencedStage: true,
    blankName: true,
    reservedBusinessFields: true,
    reservedDisabled: true,
    reservedEdge: true,
    manualFields: true,
    agentMaxRuns: true,
    edgeToBlocked: true,
    selfEdge: true,
    unreachableStage: true,
    duplicateEdge: true,
    undeclaredTarget: true,
  }

  const samples: WorkflowValidationError[] = [
    { code: 'empty' },
    { code: 'missingReserved' },
    { code: 'noDonePath' },
    { code: 'emptyCode' },
    { code: 'invalidCode', state: 'WORK' },
    { code: 'duplicateCode', state: 'WORK' },
    { code: 'referencedStage', state: 'WORK' },
    { code: 'blankName', state: 'WORK' },
    { code: 'reservedBusinessFields', state: 'BLOCKED' },
    { code: 'reservedDisabled', state: 'BLOCKED' },
    { code: 'reservedEdge', state: 'DONE' },
    { code: 'manualFields', state: 'REVIEW' },
    { code: 'agentMaxRuns', state: 'WORK', max: MAX_RUNS },
    { code: 'edgeToBlocked', state: 'WORK' },
    { code: 'selfEdge', state: 'WORK' },
    { code: 'unreachableStage', state: 'REVIEW' },
    { code: 'duplicateEdge', state: 'WORK', target: 'DONE' },
    { code: 'undeclaredTarget', state: 'WORK', target: 'GHOST' },
  ]

  it('covers every declared error code sample', () => {
    expect(samples.map((error) => error.code).sort()).toEqual(Object.keys(CODES).sort())
  })

  it('renders real copy for every code in both locales with substituted placeholders', () => {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      setLocale(locale)
      for (const error of samples) {
        const message = translate(workflowErrorKey(error), workflowErrorParams(error))
        expect(message, `${locale} ${error.code}`).not.toContain('⟦missing')
        expect(message, `${locale} ${error.code}`).not.toContain('{{')
        expect(message.trim().length, `${locale} ${error.code}`).toBeGreaterThan(0)
      }
    }
  })

  it('keeps language-specific wording and units aligned across locales', () => {
    setLocale('zh-CN')
    expect(
      translate(
        workflowErrorKey({ code: 'agentMaxRuns', state: 'WORK', max: MAX_RUNS }),
        workflowErrorParams({ code: 'agentMaxRuns', state: 'WORK', max: MAX_RUNS }),
      ),
    ).toBe(`Agent 阶段「WORK」的 Run 额度必须是 1 到 ${MAX_RUNS} 之间的整数`)
    expect(
      translate(
        workflowErrorKey({ code: 'unreachableStage', state: 'REVIEW' }),
        workflowErrorParams({ code: 'unreachableStage', state: 'REVIEW' }),
      ),
    ).toBe('启用阶段「REVIEW」无法从 INIT 到达')

    setLocale('en-US')
    expect(
      translate(
        workflowErrorKey({ code: 'agentMaxRuns', state: 'WORK', max: MAX_RUNS }),
        workflowErrorParams({ code: 'agentMaxRuns', state: 'WORK', max: MAX_RUNS }),
      ),
    ).toBe(`Stage "WORK" run budget must be an integer between 1 and ${MAX_RUNS}`)
    expect(
      translate(
        workflowErrorKey({ code: 'unreachableStage', state: 'REVIEW' }),
        workflowErrorParams({ code: 'unreachableStage', state: 'REVIEW' }),
      ),
    ).toBe('Enabled stage "REVIEW" is not reachable from INIT')
  })
})
