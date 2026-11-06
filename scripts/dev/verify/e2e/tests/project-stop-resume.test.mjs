import assert from 'node:assert/strict'
import test from 'node:test'

import { verifyIssueStopResume } from '../cases/project.mjs'
import { problemError } from './fixtures/problem.mjs'

/** 只模拟空闲 Issue 的版本和门禁；没有活动 Run，不伪造 Run 收尾。 */
function issueFixture({ allowTransition = false } = {}) {
  let issue = {
    id: '00000000-0000-4000-8000-000000000001',
    version: '8',
    state: 'WORK',
    pauseReason: null,
    pauseDetail: null,
  }
  const requests = []
  const call = async (method, path, body) => {
    requests.push({ method, path, body })
    assert.equal(method, 'POST')
    assert.equal(body.expectedVersion, issue.version)
    assert.ok(body.requestKey)
    if (path === `/api/project/issues/${issue.id}/stop`) {
      issue = {
        ...issue,
        version: String(BigInt(issue.version) + 1n),
        pauseReason: 'USER',
        pauseDetail: body.detail,
      }
    } else if (path === `/api/project/issues/${issue.id}/transition`) {
      assert.equal(body.toState, 'DONE')
      if (issue.pauseReason && !allowTransition) throw problemError(400, 'validation', 'paused', path)
      return { status: 200, json: issue }
    } else if (path === `/api/project/issues/${issue.id}/resume`) {
      issue = {
        ...issue,
        version: String(BigInt(issue.version) + 1n),
        pauseReason: null,
        pauseDetail: null,
      }
    } else {
      throw new Error(`unexpected call ${method} ${path}`)
    }
    return { status: 200, json: { ...issue } }
  }
  return { initial: { ...issue }, ctx: { call }, requests }
}

test('project lifecycle checks STOP, paused transition and resume using the returned version', async () => {
  // 真实执行 E2E 步骤，验证门禁、合法目标和 CAS 版本沿 HTTP 调用顺序传递。
  const fixture = issueFixture()
  const resumed = await verifyIssueStopResume(fixture.ctx, fixture.initial)
  assert.equal(resumed.version, '10')
  assert.equal(resumed.pauseReason, null)
  assert.equal(resumed.pauseDetail, null)
  assert.deepEqual(
    fixture.requests.map(({ path, body }) => [path.split('/').at(-1), body.expectedVersion]),
    [['stop', '8'], ['transition', '9'], ['resume', '9']],
  )
})

test('project lifecycle rejects a server that permits a paused transition', async () => {
  // 即使流转目标合法，门禁失效时 E2E 必须失败，不能继续报告恢复成功。
  const fixture = issueFixture({ allowTransition: true })
  await assert.rejects(verifyIssueStopResume(fixture.ctx, fixture.initial), /expected HttpError/)
  assert.equal(fixture.requests.length, 2)
})
