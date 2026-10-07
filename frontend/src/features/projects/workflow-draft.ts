import type { ProjectWorkflowDTO, ProjectWorkflowStateDTO } from './types'

/** 固定保留状态：必须存在，且不能配置 Agent/Environment/指令/额度，也不能停用。 */
export const RESERVED_STATE_CODES = ['INIT', 'BLOCKED', 'DONE'] as const
export type ReservedStateCode = (typeof RESERVED_STATE_CODES)[number]

export const STATE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/

/**
 * Run 额度的上界：后端 web 层把 maxRuns 解析为 long 后限制在 int 范围，领域层再要求大于 0，
 * 因此合法区间是 1..2147483647。
 */
export const MAX_RUNS = 2147483647

/** 规范十进制整数（前导无多余的 0），与后端 parseNonNegativeLong 的形态要求一致。 */
const DECIMAL_INTEGER_PATTERN = /^(0|[1-9][0-9]*)$/

/** 解析 Run 额度：仅接受 1..MAX_RUNS 的规范十进制整数，其余（含越界、小数、非数字、精度丢失）一律为 null。 */
export function parseMaxRuns(raw: string): number | null {
  const trimmed = raw.trim()
  if (!DECIMAL_INTEGER_PATTERN.test(trimmed)) {
    return null
  }
  const parsed = Number(trimmed)
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > MAX_RUNS) {
    return null
  }
  return parsed
}

/**
 * 结构化工作流编辑草稿：`maxRuns` 以字符串承载编辑中间态，其余字段与后端状态形状一一对应。
 * `next` 是正常转移白名单（集合语义）。
 */
export interface WorkflowDraftState {
  /** 稳定的本地标识：改码、重排时用于保持选中与 React 列表身份。 */
  key: string
  state: string
  name: string
  /** null 表示人工阶段。 */
  agent: string | null
  environment: string | null
  instructions: string
  maxRuns: string
  enabled: boolean
  next: string[]
}

let localKeySeq = 0

export function nextDraftKey(): string {
  localKeySeq += 1
  return `wf-${localKeySeq}`
}

export function isReservedStateCode(code: string): boolean {
  return (RESERVED_STATE_CODES as readonly string[]).includes(code)
}

export function createDraftState(state = ''): WorkflowDraftState {
  return {
    key: nextDraftKey(),
    state,
    name: '',
    agent: null,
    environment: null,
    instructions: '',
    maxRuns: '',
    enabled: true,
    next: [],
  }
}

/** 打开时的权威快照 → 编辑草稿；缺省的 enabled 视为 true。 */
export function toDraftStates(workflow: ProjectWorkflowDTO | null | undefined): WorkflowDraftState[] {
  const states = workflow?.states ?? []
  return states.map((state) => ({
    key: nextDraftKey(),
    state: state.state,
    name: state.name,
    agent: state.agent ?? null,
    environment: state.environment ?? null,
    instructions: state.instructions ?? '',
    maxRuns: state.maxRuns == null ? '' : String(state.maxRuns),
    enabled: state.enabled !== false,
    next: state.next ? [...state.next] : [],
  }))
}

/** 编辑草稿 → 整份 workflow DTO；未配置的可选字段留空，保留状态不携带业务字段。 */
export function buildWorkflowDTO(states: WorkflowDraftState[]): ProjectWorkflowDTO {
  return {
    states: states.map<ProjectWorkflowStateDTO>((state) => {
      const code = state.state.trim()
      const reserved = isReservedStateCode(code)
      const agent = reserved ? null : state.agent
      const dto: ProjectWorkflowStateDTO = {
        state: code,
        name: state.name.trim(),
        enabled: state.enabled,
        next: state.next.map((target) => target.trim()),
      }
      if (agent) {
        dto.agent = agent
        if (state.environment) {
          dto.environment = state.environment
        }
        if (state.maxRuns.trim()) {
          dto.maxRuns = state.maxRuns.trim()
        }
      }
      if (!reserved && state.instructions.trim()) {
        dto.instructions = state.instructions.trim()
      }
      return dto
    }),
  }
}

/**
 * 结构化工作流校验错误：类型化 code + 插值参数，由调用方按 locale 渲染文案
 * （`projects.workflow.error.<code>`），校验层不持有任何语言文案。
 */
export type WorkflowValidationError =
  | { code: 'empty' }
  | { code: 'missingReserved' }
  | { code: 'noDonePath' }
  | { code: 'emptyCode' }
  | { code: 'invalidCode'; state: string }
  | { code: 'duplicateCode'; state: string }
  | { code: 'referencedStage'; state: string }
  | { code: 'blankName'; state: string }
  | { code: 'reservedBusinessFields'; state: string }
  | { code: 'reservedDisabled'; state: string }
  | { code: 'reservedEdge'; state: string }
  | { code: 'manualFields'; state: string }
  | { code: 'agentMaxRuns'; state: string; max: number }
  | { code: 'edgeToBlocked'; state: string }
  | { code: 'selfEdge'; state: string }
  | { code: 'unreachableStage'; state: string }
  | { code: 'duplicateEdge'; state: string; target: string }
  | { code: 'undeclaredTarget'; state: string; target: string }

/** 错误 code 对应的 i18n 文案 key。 */
export function workflowErrorKey(error: WorkflowValidationError): string {
  return `projects.workflow.error.${error.code}`
}

/** 错误携带的插值参数（占位符名称与 catalog 中的双语文案一致）。 */
export function workflowErrorParams(
  error: WorkflowValidationError,
): Record<string, string | number> {
  if ('target' in error) {
    return { state: error.state, target: error.target }
  }
  if ('max' in error) {
    return { state: error.state, max: error.max }
  }
  if ('state' in error) {
    return { state: error.state }
  }
  return {}
}

/**
 * 编辑草稿校验，与 `ProjectWorkflow` / `ProjectWorkflowState` / `ProjectStateCode`
 * 以及 `ProjectServiceImpl.requireReferencedStatesRetained` 保持同一组约束。
 *
 * @param referencedStates 现存 Issue（含归档）当前状态与阻塞来源的编码集合；这些阶段不可删除。
 * @returns 第一条结构化错误；合法时为 null。
 */
export function validateWorkflowDraft(
  states: WorkflowDraftState[],
  referencedStates: ReadonlySet<string> = new Set(),
): WorkflowValidationError | null {
  if (states.length === 0) {
    return { code: 'empty' }
  }

  for (const state of states) {
    const code = state.state.trim()
    if (!code) {
      return { code: 'emptyCode' }
    }
    if (!STATE_CODE_PATTERN.test(code)) {
      return { code: 'invalidCode', state: code }
    }
  }

  const codes = new Set<string>()
  for (const state of states) {
    const code = state.state.trim()
    if (codes.has(code)) {
      return { code: 'duplicateCode', state: code }
    }
    codes.add(code)
  }

  for (const reserved of RESERVED_STATE_CODES) {
    if (!codes.has(reserved)) {
      return { code: 'missingReserved' }
    }
  }

  for (const code of referencedStates) {
    if (!codes.has(code)) {
      return { code: 'referencedStage', state: code }
    }
  }

  for (const state of states) {
    if (!state.name.trim()) {
      return { code: 'blankName', state: state.state.trim() }
    }
  }

  for (const state of states) {
    const code = state.state.trim()
    if (isReservedStateCode(code)) {
      if (state.agent || state.environment || state.instructions.trim() || state.maxRuns.trim()) {
        return { code: 'reservedBusinessFields', state: code }
      }
      if (!state.enabled) {
        return { code: 'reservedDisabled', state: code }
      }
      if (code !== 'INIT' && state.next.length > 0) {
        return { code: 'reservedEdge', state: code }
      }
      continue
    }
    if (!state.agent) {
      if (state.environment || state.maxRuns.trim()) {
        return { code: 'manualFields', state: code }
      }
      continue
    }
    if (parseMaxRuns(state.maxRuns) === null) {
      return { code: 'agentMaxRuns', state: code, max: MAX_RUNS }
    }
  }

  for (const state of states) {
    const code = state.state.trim()
    const seen = new Set<string>()
    for (const target of state.next) {
      if (target === 'BLOCKED') {
        return { code: 'edgeToBlocked', state: code }
      }
      if (target === code) {
        return { code: 'selfEdge', state: code }
      }
      if (seen.has(target)) {
        return { code: 'duplicateEdge', state: code, target }
      }
      seen.add(target)
      if (!codes.has(target)) {
        return { code: 'undeclaredTarget', state: code, target }
      }
    }
  }

  const reachable = reachableFromInit(states)
  for (const state of states) {
    const code = state.state.trim()
    if (state.enabled && !isReservedStateCode(code) && !reachable.has(code)) {
      return { code: 'unreachableStage', state: code }
    }
  }
  if (!reachable.has('DONE')) {
    return { code: 'noDonePath' }
  }

  return null
}

function reachableFromInit(states: WorkflowDraftState[]): Set<string> {
  const nextByState = new Map<string, string[]>()
  for (const state of states) {
    nextByState.set(
      state.state.trim(),
      state.next.map((target) => target.trim()),
    )
  }
  const visited = new Set<string>(['INIT'])
  const queue: string[] = ['INIT']
  while (queue.length > 0) {
    const current = queue.shift()!
    for (const target of nextByState.get(current) ?? []) {
      if (!visited.has(target)) {
        visited.add(target)
        queue.push(target)
      }
    }
  }
  return visited
}

/** 项目是否仍有活动 Run（RUNNING / WAITING），活动 Run 期间不允许改写工作流。 */
export function hasActiveRun(
  issues: ReadonlyArray<{ currentOrLatestRun?: { status: string } | null }> | undefined,
): boolean {
  return (issues ?? []).some(
    (item) =>
      item.currentOrLatestRun?.status === 'RUNNING' || item.currentOrLatestRun?.status === 'WAITING',
  )
}
