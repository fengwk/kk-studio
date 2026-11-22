import { afterEach, describe, expect, it } from 'vitest'
import type { IssueRunDTO } from '@/shared/api/generated/contracts'
import { setLocale, translate } from '@/shared/i18n'
import { formatDuration, runDurationText } from './issue-duration'

afterEach(() => {
  // 语言是模块级状态，避免污染其他用例。
  setLocale('zh-CN')
})

const STARTED_AT = Date.UTC(2026, 10, 22, 8, 0, 0)

const run = (overrides: Partial<IssueRunDTO>): IssueRunDTO => ({
  id: 'run-1',
  issueId: 'issue-1',
  ordinal: '1',
  state: 'WORK',
  agentName: 'backend-dev',
  sessionId: 'session-1',
  threadId: 'thread-1',
  status: 'RUNNING',
  startEntryId: 'entry-1',
  endEntryId: null,
  finalAnswerEntryId: null,
  nextState: null,
  observedActivitySequence: '1',
  remainingExecutionMs: '120000',
  error: null,
  version: '1',
  startedAt: STARTED_AT,
  endedAt: null,
  ...overrides,
})

describe('formatDuration', () => {
  it('中文按最多两级单位呈现，小时级不补秒', () => {
    expect(formatDuration(45_000, translate)).toBe('45 秒')
    expect(formatDuration(90_500, translate)).toBe('1 分钟 30 秒')
    // 不足 30 分钟时仍展示 29 分钟 59 秒，不进位夸大剩余预算。
    expect(formatDuration(1_799_996, translate)).toBe('29 分钟 59 秒')
    expect(formatDuration(5_400_000, translate)).toBe('1 小时 30 分钟')
    expect(formatDuration(3_600_000, translate)).toBe('1 小时')
  })

  it('英文使用同义的单位缩写，语义与中文一致', () => {
    setLocale('en-US')
    expect(formatDuration(45_000, translate)).toBe('45s')
    expect(formatDuration(90_500, translate)).toBe('1m 30s')
    expect(formatDuration(1_799_996, translate)).toBe('29m 59s')
    expect(formatDuration(5_400_000, translate)).toBe('1h 30m')
    expect(formatDuration(3_600_000, translate)).toBe('1h')
  })

  it('零与负时长显示为 0 秒，不出现负号', () => {
    expect(formatDuration(0, translate)).toBe('0 秒')
    expect(formatDuration(-5_000, translate)).toBe('0 秒')
  })
})

describe('runDurationText', () => {
  it('终态 Run 的耗时只由起止时间戳得出，不把剩余预算当耗时', () => {
    // 已完成的 Run 仍带着接近预算上限的剩余余额：界面只报真实耗时。
    const completed = run({
      status: 'COMPLETED',
      endedAt: STARTED_AT + 125_000,
      endEntryId: 'entry-9',
      remainingExecutionMs: '1799996',
    })

    expect(runDurationText(completed, translate)).toBe('耗时 2 分钟 5 秒')
    expect(runDurationText(completed, translate)).not.toContain('剩余执行预算')
    expect(runDurationText(completed, translate)).not.toContain('1799996')
  })

  it('活动 Run 只报本地化的剩余预算，不编造耗时', () => {
    const running = run({ status: 'RUNNING', remainingExecutionMs: '120000' })
    const waiting = run({ status: 'WAITING', remainingExecutionMs: '5400000' })

    expect(runDurationText(running, translate)).toBe('剩余执行预算 2 分钟')
    expect(runDurationText(waiting, translate)).toBe('剩余执行预算 1 小时 30 分钟')
    setLocale('en-US')
    expect(runDurationText(running, translate)).toBe('Remaining execution budget 2m')
  })
})
