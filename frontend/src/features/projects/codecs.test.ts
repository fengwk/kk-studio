import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  decodeIssue,
  decodeIssueDependency,
  decodeIssueDependencyList,
  decodeIssueDetail,
  decodeIssueInput,
  decodeIssueRun,
  decodeIssueRunSummary,
  decodeProject,
  decodeProjectIssueSnapshot,
  decodeProjectList,
  decodeProjectSnapshot,
  isCanonicalUuid,
  isDecimalLong,
  isIssueStatus,
} from './codecs'

describe('projects codecs', () => {
  describe('predicate validators', () => {
    it('isCanonicalUuid should validate canonical lowercase and uppercase UUIDs', () => {
      // 测试意图：验证 UUID 校验函数能严格识别合法 canonical UUID 并拒绝格式不符的字符串
      expect(isCanonicalUuid('123e4567-e89b-12d3-a456-426614174000')).toBe(true)
      expect(isCanonicalUuid('123E4567-E89B-12D3-A456-426614174000')).toBe(true)
      expect(isCanonicalUuid('not-a-uuid')).toBe(false)
      expect(isCanonicalUuid('')).toBe(false)
      expect(isCanonicalUuid(null)).toBe(false)
      expect(isCanonicalUuid(123)).toBe(false)
    })

    it('isDecimalLong should validate non-negative integer decimal string without decimal point', () => {
      // 测试意图：验证非负十进制 long 字符串验证规则，保证零或正整数字符串通过，负数或浮点数被拒绝
      expect(isDecimalLong('0')).toBe(true)
      expect(isDecimalLong('1')).toBe(true)
      expect(isDecimalLong('9007199254740993')).toBe(true)
      expect(isDecimalLong('-1')).toBe(false)
      expect(isDecimalLong('1.5')).toBe(false)
      expect(isDecimalLong('abc')).toBe(false)
      expect(isDecimalLong(0)).toBe(false)
    })

    it('isIssueStatus should validate defined issue statuses', () => {
      // 测试意图：验证 IssueStatus 严格限定为 BACKLOG/TODO/IN_PROGRESS/IN_REVIEW/DONE/CANCELED
      expect(isIssueStatus('BACKLOG')).toBe(true)
      expect(isIssueStatus('TODO')).toBe(true)
      expect(isIssueStatus('IN_PROGRESS')).toBe(true)
      expect(isIssueStatus('IN_REVIEW')).toBe(true)
      expect(isIssueStatus('DONE')).toBe(true)
      expect(isIssueStatus('CANCELED')).toBe(true)
      expect(isIssueStatus('INVALID')).toBe(false)
      expect(isIssueStatus('')).toBe(false)
      expect(isIssueStatus(null)).toBe(false)
    })
  })

  describe('decodeProject and decodeProjectList', () => {
    const validProject = {
      id: 'a0000000-0000-0000-0000-000000000001',
      title: 'Test Project',
      description: 'Project description',
      coordinatorAgentName: 'coordinator-agent',
      nextIssueNumber: '1',
      version: '0',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T00:00:00Z',
    }

    it('decodeProject should decode valid project payload', () => {
      // 测试意图：验证合法 Project JSON 负载能够被严格解码为 ProjectDTO
      const decoded = decodeProject(validProject)
      expect(decoded.id).toBe('a0000000-0000-0000-0000-000000000001')
      expect(decoded.title).toBe('Test Project')
      expect(decoded.description).toBe('Project description')
      expect(decoded.coordinatorAgentName).toBe('coordinator-agent')
      expect(decoded.nextIssueNumber).toBe('1')
      expect(decoded.version).toBe('0')
      expect(decoded.archivedAt).toBeNull()
    })

    it('decodeProject should reject a null description', () => {
      // 测试意图：响应 description 是必需字符串；拒绝旧式空值而不是静默改写事实。
      expect(() => decodeProject({ ...validProject, description: null })).toThrow(ApiError)
    })

    it('decodeProject should throw ApiError on invalid UUID or missing title', () => {
      // 测试意图：验证非法 ID 会触发 ApiError 异常以满足 fail-closed 契约
      expect(() => decodeProject({ ...validProject, id: 'bad-id' })).toThrow(ApiError)
      expect(() => decodeProject({ ...validProject, title: 123 })).toThrow(ApiError)
      expect(() => decodeProject({ ...validProject, version: '-5' })).toThrow(ApiError)
      expect(() => decodeProject({ ...validProject, coordinatorAgentName: null })).toThrow(ApiError)
      expect(() => decodeProject('not-an-object')).toThrow(ApiError)
    })

    it('decodeProjectList should decode an array of projects', () => {
      // 测试意图：验证项目列表解码器可批量解析并校验多条记录
      const list = decodeProjectList([validProject])
      expect(list).toHaveLength(1)
      expect(list[0].id).toBe(validProject.id)
    })

    it('decodeProjectList should reject non-array payload', () => {
      // 测试意图：非数组负载时抛出异常
      expect(() => decodeProjectList(validProject)).toThrow(ApiError)
    })
  })

  describe('decodeIssue and related decoders', () => {
    const validIssue = {
      id: 'b0000000-0000-0000-0000-000000000001',
      projectId: 'a0000000-0000-0000-0000-000000000001',
      number: '42',
      title: 'Fix issue',
      description: 'Detailed description',
      status: 'TODO',
      assigneeAgentName: 'dev-agent',
      reviewerAgentName: null,
      version: '1',
      specRevision: '2',
      inputSequence: '0',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T01:00:00Z',
    }

    it('decodeIssue should decode valid issue payload', () => {
      // 测试意图：验证合法 Issue JSON 负载被正确解码且包含完整属性
      const decoded = decodeIssue(validIssue)
      expect(decoded.id).toBe('b0000000-0000-0000-0000-000000000001')
      expect(decoded.projectId).toBe('a0000000-0000-0000-0000-000000000001')
      expect(decoded.number).toBe('42')
      expect(decoded.status).toBe('TODO')
      expect(decoded.assigneeAgentName).toBe('dev-agent')
      expect(decoded.reviewerAgentName).toBeNull()
      expect(decoded.version).toBe('1')
    })

    it('decodeIssue should reject unknown status', () => {
      // 测试意图：验证未知状态码被拒绝以防非法状态流入
      expect(() => decodeIssue({ ...validIssue, status: 'UNKNOWN_STATUS' })).toThrow(ApiError)
    })

    it('decodeIssueDependency and decodeIssueDependencyList', () => {
      // 测试意图：验证 IssueDependency 边结构及其集合解码
      const dep = {
        issueId: 'b0000000-0000-0000-0000-000000000001',
        dependsOnIssueId: 'b0000000-0000-0000-0000-000000000002',
        projectId: 'a0000000-0000-0000-0000-000000000001',
        createdAt: '2026-09-14T00:00:00Z',
      }
      const decoded = decodeIssueDependency(dep)
      expect(decoded.issueId).toBe(dep.issueId)
      expect(decoded.dependsOnIssueId).toBe(dep.dependsOnIssueId)

      const list = decodeIssueDependencyList([dep])
      expect(list).toHaveLength(1)
    })

    it('decodeIssueInput should decode input entries', () => {
      // 测试意图：验证 IssueInput 结构解码与 nullable idempotencyKey
      const input = {
        issueId: 'b0000000-0000-0000-0000-000000000001',
        sequence: '1',
        kind: 'HUMAN',
        body: 'Please proceed with approach A',
        idempotencyKey: 'input-key-1',
        createdAt: '2026-09-14T00:00:00Z',
      }
      const decoded = decodeIssueInput(input)
      expect(decoded.sequence).toBe('1')
      expect(decoded.kind).toBe('HUMAN')
      expect(decoded.body).toBe('Please proceed with approach A')
      expect(decoded.idempotencyKey).toBe('input-key-1')
    })

    it('decodeIssueRunSummary should decode summary with nullable timestamps and outcomes', () => {
      // 测试意图：验证 IssueRunSummary 摘要解码
      const summary = {
        id: 'd0000000-0000-0000-0000-000000000001',
        issueId: 'b0000000-0000-0000-0000-000000000001',
        ordinal: '1',
        role: 'EXECUTOR',
        actorType: 'AGENT',
        agentName: 'dev-agent',
        submissionRunId: null,
        status: 'RUNNING',
        outcome: null,
        waitingReason: null,
        createdAt: '2026-09-14T00:00:00Z',
        completedAt: null,
      }
      const decoded = decodeIssueRunSummary(summary)
      expect(decoded.role).toBe('EXECUTOR')
      expect(decoded.status).toBe('RUNNING')
      expect(decoded.completedAt).toBeNull()
    })

    it('decodeIssueRun should decode complete run fact', () => {
      // 测试意图：验证完整 IssueRun 详细对象解码
      const run = {
        id: 'd0000000-0000-0000-0000-000000000001',
        issueId: 'b0000000-0000-0000-0000-000000000001',
        ordinal: '1',
        role: 'EXECUTOR',
        actorType: 'AGENT',
        agentName: 'dev-agent',
        submissionRunId: null,
        status: 'COMPLETED',
        outcome: 'SUBMITTED',
        observedSpecRevision: '1',
        observedInputSequence: '0',
        continuationCount: 2,
        maxContinuations: 10,
        deadline: null,
        waitingReason: null,
        result: 'Finished implementation',
        terminalActionId: 'e0000000-0000-0000-0000-000000000001',
        version: '3',
        createdAt: '2026-09-14T00:00:00Z',
        updatedAt: '2026-09-14T00:05:00Z',
        completedAt: '2026-09-14T00:05:00Z',
        sessionId: 'f0000000-0000-0000-0000-000000000001',
      }
      const decoded = decodeIssueRun(run)
      expect(decoded.outcome).toBe('SUBMITTED')
      expect(decoded.continuationCount).toBe(2)
      expect(decoded.sessionId).toBe('f0000000-0000-0000-0000-000000000001')
    })
  })

  describe('decodeProjectSnapshot and decodeIssueDetail', () => {
    it('decodeProjectSnapshot should decode complete snapshot with issues and session summaries', () => {
      // 测试意图：验证权威 ProjectSnapshot 聚合对象解码，包含 Project、Issue 投影条目与 Coordinator Session
      const snapshot = {
        project: {
          id: 'a0000000-0000-0000-0000-000000000001',
          title: 'Snapshot Test',
          description: '',
          coordinatorAgentName: 'coord',
          nextIssueNumber: '2',
          version: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
        },
        issues: [
          {
            issue: {
              id: 'b0000000-0000-0000-0000-000000000001',
              projectId: 'a0000000-0000-0000-0000-000000000001',
              number: '1',
              title: 'First issue',
              description: '',
              status: 'BACKLOG',
              assigneeAgentName: null,
              reviewerAgentName: null,
              version: '0',
              specRevision: '0',
              inputSequence: '0',
              archivedAt: null,
              createdAt: '2026-09-14T00:00:00Z',
              updatedAt: '2026-09-14T00:00:00Z',
            },
            blocked: false,
            currentOrLatestRun: null,
          },
        ],
        dependencies: [],
        coordinatorSessionId: 'f0000000-0000-0000-0000-000000000001',
        coordinatorSession: {
          sessionId: 'f0000000-0000-0000-0000-000000000001',
          name: 'Coordinator Session',
          createdAt: '2026-09-14T00:00:00Z',
          lastActivityAt: '2026-09-14T00:00:00Z',
          firstMessagePreview: 'Hello project',
          threadCount: 1,
        },
        coordinatorThread: {
          threadId: 'f0000000-0000-0000-0000-000000000002',
          name: 'main',
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
          status: 'IDLE',
          model: {
            providerName: 'openai',
            modelName: 'gpt-4o',
            variant: 'default',
          },
          headMessagePreview: 'Hello project',
        },
      }

      const decoded = decodeProjectSnapshot(snapshot)
      expect(decoded.project.title).toBe('Snapshot Test')
      expect(decoded.issues).toHaveLength(1)
      expect(decoded.issues[0].blocked).toBe(false)
      expect(decoded.coordinatorSessionId).toBe('f0000000-0000-0000-0000-000000000001')
      expect(decoded.coordinatorSession?.name).toBe('Coordinator Session')
      expect(decoded.coordinatorThread?.status).toBe('IDLE')
    })

    it('decodeProjectIssueSnapshot should decode item with run', () => {
      // 测试意图：验证 issue snapshot 带有 active/latest run 时的正确解码
      const item = {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000001',
          projectId: 'a0000000-0000-0000-0000-000000000001',
          number: '1',
          title: 'First issue',
          description: '',
          status: 'IN_PROGRESS',
          assigneeAgentName: 'dev-agent',
          reviewerAgentName: null,
          version: '1',
          specRevision: '1',
          inputSequence: '0',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
        },
        blocked: true,
        currentOrLatestRun: {
          id: 'd0000000-0000-0000-0000-000000000001',
          issueId: 'b0000000-0000-0000-0000-000000000001',
          ordinal: '1',
          role: 'EXECUTOR',
          actorType: 'AGENT',
          agentName: 'dev-agent',
          submissionRunId: null,
          status: 'WAITING_HUMAN',
          outcome: null,
          waitingReason: 'Awaiting human choice',
          createdAt: '2026-09-14T00:00:00Z',
          completedAt: null,
        },
      }

      const decoded = decodeProjectIssueSnapshot(item)
      expect(decoded.blocked).toBe(true)
      expect(decoded.currentOrLatestRun?.status).toBe('WAITING_HUMAN')
      expect(decoded.currentOrLatestRun?.waitingReason).toBe('Awaiting human choice')
    })

    it('decodeIssueDetail should decode full issue detail DTO', () => {
      // 测试意图：验证 Issue 详情页聚合 DTO 包含 runs、dependencies、inputs
      const detail = {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000001',
          projectId: 'a0000000-0000-0000-0000-000000000001',
          number: '1',
          title: 'Detail test',
          description: 'Spec',
          status: 'IN_PROGRESS',
          assigneeAgentName: null,
          reviewerAgentName: null,
          version: '1',
          specRevision: '1',
          inputSequence: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
        },
        blocked: false,
        dependencies: [],
        inputs: [
          {
            issueId: 'b0000000-0000-0000-0000-000000000001',
            sequence: '1',
            kind: 'HUMAN',
            body: 'Input #1',
            idempotencyKey: null,
            createdAt: '2026-09-14T00:01:00Z',
          },
        ],
        runs: [],
        currentRun: null,
        latestRun: null,
      }

      const decoded = decodeIssueDetail(detail)
      expect(decoded.issue.title).toBe('Detail test')
      expect(decoded.inputs).toHaveLength(1)
      expect(decoded.inputs[0].body).toBe('Input #1')
      expect(decoded.currentRun).toBeNull()
    })
  })
})
