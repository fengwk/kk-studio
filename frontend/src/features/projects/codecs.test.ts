import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  decodeIssue,
  decodeIssueActivity,
  decodeIssueActivityList,
  decodeIssueAgentThread,
  decodeIssueDetail,
  decodeIssueEvidence,
  decodeIssueEvidenceList,
  decodeIssueRun,
  decodeIssueRunSummary,
  decodeIssueStageBudget,
  decodeProject,
  decodeProjectIssueSnapshot,
  decodeProjectList,
  decodeProjectSnapshot,
  decodeProjectWorkflow,
  decodeProjectWorkflowState,
  isCanonicalUuid,
  isDecimalLong,
  isValidStateCode,
} from './codecs'

describe('projects codecs', () => {
  const validProject = {
    id: 'a0000000-0000-0000-0000-000000000001',
    title: 'Test Project',
    description: 'Project description',
    workflow: {
      states: [
        { state: 'INIT', name: '待开始', next: ['DONE'] },
        { state: 'DONE', name: '完成' },
      ],
    },
    yoloEnabled: true,
    nextIssueNumber: '1',
    version: '0',
    archivedAt: null,
    createdAt: '2026-09-14T00:00:00Z',
    updatedAt: '2026-09-14T00:00:00Z',
  }

  describe('predicate validators', () => {
    it('isCanonicalUuid should validate canonical UUIDs', () => {
      // 测试意图：验证 UUID 格式检查严格匹配 36 位十六进制 UUID
      expect(isCanonicalUuid('123e4567-e89b-12d3-a456-426614174000')).toBe(true)
      expect(isCanonicalUuid('123E4567-E89B-12D3-A456-426614174000')).toBe(true)
      expect(isCanonicalUuid('not-a-uuid')).toBe(false)
      expect(isCanonicalUuid('')).toBe(false)
      expect(isCanonicalUuid(null)).toBe(false)
      expect(isCanonicalUuid(123)).toBe(false)
    })

    it('isDecimalLong should validate non-negative integer decimal string', () => {
      // 测试意图：验证非负十进制 long 字符串验证规则，保证非负整数通过，拒绝负数与浮点
      expect(isDecimalLong('0')).toBe(true)
      expect(isDecimalLong('1')).toBe(true)
      expect(isDecimalLong('9007199254740993')).toBe(true)
      expect(isDecimalLong('-1')).toBe(false)
      expect(isDecimalLong('1.5')).toBe(false)
      expect(isDecimalLong('abc')).toBe(false)
    })

    it('isValidStateCode should validate natural state token format', () => {
      // 测试意图：验证状态自然编码必须匹配 [A-Z][A-Z0-9_]{0,63}
      expect(isValidStateCode('INIT')).toBe(true)
      expect(isValidStateCode('DESIGN_V2')).toBe(true)
      expect(isValidStateCode('DONE')).toBe(true)
      expect(isValidStateCode('lower_case')).toBe(false)
      expect(isValidStateCode('123_STATE')).toBe(false)
      expect(isValidStateCode('')).toBe(false)
    })
  })

  describe('workflow codecs', () => {
    it('decodeProjectWorkflowState should parse valid state and next transitions', () => {
      // 测试意图：验证工作流状态配置解析，包含 agent、environment、maxRuns 和合法转移白名单
      const state = decodeProjectWorkflowState({
        state: 'DESIGN',
        name: '设计方案',
        agent: 'designer',
        environment: 'dev-env',
        instructions: '完成设计',
        maxRuns: 3,
        enabled: true,
        next: ['REVIEW'],
      })
      expect(state.state).toBe('DESIGN')
      expect(state.name).toBe('设计方案')
      expect(state.agent).toBe('designer')
      expect(state.maxRuns).toBe('3')
      expect(state.next).toEqual(['REVIEW'])
    })

    it('decodeProjectWorkflowState should throw on invalid state token', () => {
      // 测试意图：不符合命名规范的状态编码应触发 ApiError
      expect(() =>
        decodeProjectWorkflowState({ state: 'invalid-name', name: 'Bad' }),
      ).toThrow(ApiError)
    })

    it('decodeProjectWorkflow should parse complete workflow object', () => {
      // 测试意图：验证完整工作流对象中多状态列表解析
      const wf = decodeProjectWorkflow({
        states: [
          { state: 'INIT', name: '待开始', next: ['WORK'] },
          { state: 'WORK', name: '工作中', next: ['DONE'] },
          { state: 'DONE', name: '完成' },
        ],
      })
      expect(wf.states).toHaveLength(3)
      expect(wf.states[0].state).toBe('INIT')
    })
  })

  describe('decodeProject and decodeProjectList', () => {
    const validProject = {
      id: 'a0000000-0000-0000-0000-000000000001',
      title: 'Test Project',
      description: 'Project description',
      workflow: {
        states: [
          { state: 'INIT', name: '待开始', next: ['DONE'] },
          { state: 'DONE', name: '完成' },
        ],
      },
      yoloEnabled: true,
      nextIssueNumber: '1',
      version: '0',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T00:00:00Z',
    }

    it('decodeProject should decode valid project payload', () => {
      // 测试意图：验证包含动态 workflow 与 yoloEnabled 的合法 Project JSON 正确解码
      const decoded = decodeProject(validProject)
      expect(decoded.id).toBe('a0000000-0000-0000-0000-000000000001')
      expect(decoded.title).toBe('Test Project')
      expect(decoded.workflow.states).toHaveLength(2)
      expect(decoded.yoloEnabled).toBe(true)
      expect(decoded.version).toBe('0')
    })

    it('decodeProjectList should decode array of projects', () => {
      // 测试意图：验证数组解码逻辑
      const list = decodeProjectList([validProject])
      expect(list).toHaveLength(1)
      expect(list[0].id).toBe(validProject.id)
    })

    it('decodeProject should reject invalid uuid or missing fields', () => {
      // 测试意图：验证缺少必要属性或非法值抛出 ApiError
      expect(() => decodeProject({ ...validProject, id: 'bad' })).toThrow(ApiError)
      expect(() => decodeProject({ ...validProject, title: 123 })).toThrow(ApiError)
      expect(() => decodeProject({ ...validProject, workflow: null })).toThrow(ApiError)
    })
  })

  describe('decodeIssue and issue components', () => {
    const validIssue = {
      id: 'b0000000-0000-0000-0000-000000000001',
      projectId: 'a0000000-0000-0000-0000-000000000001',
      number: '1',
      title: 'Issue Title',
      description: 'Issue Desc',
      state: 'INIT',
      blockedFromState: null,
      blockReason: null,
      pauseReason: null,
      pauseDetail: null,
      version: '1',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T00:00:00Z',
    }

    it('decodeIssue should decode valid issue with pauseReason and blocked state', () => {
      // 测试意图：验证 Issue 状态自然 token、pauseReason (USER/ERROR/UNKNOWN) 和阻塞恢复来源
      const decoded = decodeIssue({
        ...validIssue,
        state: 'BLOCKED',
        blockedFromState: 'DESIGN',
        blockReason: 'Missing spec',
        pauseReason: 'UNKNOWN',
        pauseDetail: 'Worker crash',
      })
      expect(decoded.state).toBe('BLOCKED')
      expect(decoded.blockedFromState).toBe('DESIGN')
      expect(decoded.blockReason).toBe('Missing spec')
      expect(decoded.pauseReason).toBe('UNKNOWN')
      expect(decoded.pauseDetail).toBe('Worker crash')
    })

    it('decodeIssueAgentThread should decode agent thread binding', () => {
      // 测试意图：验证 Issue 与 Agent 稳定 Thread 映射解码
      const thread = decodeIssueAgentThread({
        issueId: validIssue.id,
        agentName: 'designer',
        threadId: 'c0000000-0000-0000-0000-000000000001',
      })
      expect(thread.agentName).toBe('designer')
      expect(thread.threadId).toBe('c0000000-0000-0000-0000-000000000001')
    })

    it('decodeIssueStageBudget should decode stage budget view', () => {
      // 测试意图：验证阶段预算 DTO 解码与高水位 Run 序号检查
      const budget = decodeIssueStageBudget({
        state: 'DESIGN',
        maxRuns: 3,
        budgetAfterOrdinal: '0',
        usedRuns: '1',
        remainingRuns: '2',
      })
      expect(budget.state).toBe('DESIGN')
      expect(budget.maxRuns).toBe(3)
      expect(budget.usedRuns).toBe('1')
      expect(budget.remainingRuns).toBe('2')
    })

    it('decodeIssueRunSummary should decode run summary', () => {
      // 测试意图：验证 Run 简略信息解码
      const summary = decodeIssueRunSummary({
        id: 'd0000000-0000-0000-0000-000000000001',
        issueId: validIssue.id,
        ordinal: '1',
        state: 'DESIGN',
        status: 'RUNNING',
        agentName: 'designer',
        startedAt: '2026-09-14T00:00:00Z',
        endedAt: null,
      })
      expect(summary.ordinal).toBe('1')
      expect(summary.status).toBe('RUNNING')
    })

    it('decodeIssueRun should decode complete issue run facts', () => {
      // 测试意图：验证 Run 完整执行事实解码，包括区间坐标、最终报告与交接目标
      const run = decodeIssueRun({
        id: 'd0000000-0000-0000-0000-000000000001',
        issueId: validIssue.id,
        ordinal: '1',
        state: 'DESIGN',
        agentName: 'designer',
        sessionId: 'e0000000-0000-0000-0000-000000000001',
        threadId: 'c0000000-0000-0000-0000-000000000001',
        status: 'COMPLETED',
        startEntryId: 'f0000000-0000-0000-0000-000000000001',
        endEntryId: 'f0000000-0000-0000-0000-000000000002',
        finalAnswerEntryId: 'f0000000-0000-0000-0000-000000000002',
        nextState: 'REVIEW',
        observedActivitySequence: '5',
        remainingExecutionMs: '120000',
        error: null,
        version: '2',
        startedAt: '2026-09-14T00:00:00Z',
        endedAt: '2026-09-14T00:05:00Z',
      })
      expect(run.status).toBe('COMPLETED')
      expect(run.nextState).toBe('REVIEW')
      expect(run.finalAnswerEntryId).toBe('f0000000-0000-0000-0000-000000000002')
    })

    it('decodeIssueActivity should decode activities with COMMENT or INSTRUCTION kind', () => {
      // 测试意图：验证活动事实流解码，正确区分普通 COMMENT 与 Run 派发 INSTRUCTION
      const act = decodeIssueActivity({
        issueId: validIssue.id,
        sequence: '1',
        kind: 'INSTRUCTION',
        actorType: 'HUMAN',
        actorAgentName: null,
        runId: 'd0000000-0000-0000-0000-000000000001',
        body: 'Please refine details',
        data: null,
        createdAt: '2026-09-14T00:00:00Z',
      })
      expect(act.kind).toBe('INSTRUCTION')
      expect(act.body).toBe('Please refine details')

      const actList = decodeIssueActivityList([act])
      expect(actList).toHaveLength(1)
    })

    it('decodeIssueEvidence should decode evidence records', () => {
      // 测试意图：验证公开证据信息解码
      const ev = decodeIssueEvidence({
        issueId: validIssue.id,
        blobId: 'blob-1',
        uri: 'kkstudio:/resources/blob-1',
        name: 'spec.pdf',
        actorAgentName: 'designer',
        runId: 'd0000000-0000-0000-0000-000000000001',
        createdAt: '2026-09-14T00:00:00Z',
      })
      expect(ev.name).toBe('spec.pdf')
      expect(ev.actorAgentName).toBe('designer')

      const evList = decodeIssueEvidenceList([ev])
      expect(evList).toHaveLength(1)
    })

    it('decodeIssueDetail should decode full issue aggregate', () => {
      // 测试意图：验证 IssueDetail 聚合读取面解码，包含 Issue、Activities、Runs、Budgets 和 AgentThreads
      const detail = decodeIssueDetail({
        issue: validIssue,
        activities: [],
        nextActivityCursor: '10',
        runs: [],
        currentRun: null,
        latestRun: null,
        stageBudgets: [],
        agentThreads: [],
      })
      expect(detail.issue.id).toBe(validIssue.id)
      expect(detail.nextActivityCursor).toBe('10')
      expect(detail.stageBudgets).toEqual([])
    })

    it('decodeProjectSnapshot should decode project and issues without dependencies', () => {
      // 测试意图：验证 ProjectSnapshot 只包含 project 和 issues，不含旧 dependencies
      const snap = decodeProjectSnapshot({
        project: validProject,
        issues: [
          {
            issue: validIssue,
            currentOrLatestRun: null,
          },
        ],
      })
      expect(snap.project.id).toBe(validProject.id)
      expect(snap.issues).toHaveLength(1)
    })
  })
})
