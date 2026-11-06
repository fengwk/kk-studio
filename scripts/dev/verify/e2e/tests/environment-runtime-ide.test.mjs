import assert from 'node:assert/strict'
import test from 'node:test'

import { HttpError } from '../lib/http.mjs'
import { createEnvironmentFixture, deleteEnvironmentFixture } from '../lib/fixtures.mjs'
import { ALL_CASES, getCase } from '../run-matrix.mjs'

const RUNTIME_CASE_ID = 'environment.runtime_absent_unavailable'
const IDE_CASE_ID = 'environment.ide_view_no_ready_owner_rejected'
const ENVIRONMENT_ID = '00000000-0000-4000-8000-000000000abc'

function canonicalErrorBody(status, code, detail, reason) {
  return JSON.stringify({
    type: 'about:blank',
    title: 'canonical error',
    status,
    code,
    detail,
    ...(reason ? { reason } : {}),
  })
}

/**
 * 只 fake HTTP 的 Environment fixture：建模“已登记但没有任何 READY Daemon”的 Environment，
 * 用来真实执行 runtime/IDE case 并观察它发出的 method/path/body。
 */
function environmentWithoutDaemon() {
  const calls = []
  let version = '0'
  let deleted = false
  const ctx = {
    async call(method, path, body) {
      calls.push({ method, path, body })
      if (method === 'POST' && path === '/api/environments') {
        return { json: { id: ENVIRONMENT_ID, name: body.name, version } }
      }
      if (method === 'GET' && path === `/api/environments/${ENVIRONMENT_ID}`) {
        return { json: { id: ENVIRONMENT_ID, version } }
      }
      if (method === 'DELETE' && path.startsWith(`/api/environments/${ENVIRONMENT_ID}?`)) {
        deleted = true
        return { json: null }
      }
      if (path.includes('not-a-uuid')) {
        throw new HttpError(
          400,
          canonicalErrorBody(400, 'validation', 'environmentId must be a canonical UUID'),
          path,
        )
      }
      if (path.endsWith('/runtime')) {
        throw new HttpError(
          409,
          canonicalErrorBody(409, 'unavailable', 'environment has no current READY owner route', 'ENVIRONMENT_UNAVAILABLE'),
          path,
        )
      }
      if (path.includes('/api/ide/environments/')) {
        throw new HttpError(
          409,
          canonicalErrorBody(409, 'unavailable', 'environment IDE owner route is unavailable', 'IDE_UNAVAILABLE'),
          path,
        )
      }
      throw new Error(`unexpected call ${method} ${path}`)
    },
  }
  return { ctx, calls, isDeleted: () => deleted }
}

test('runtime and IDE cases are registered as free L1 matrix cases', () => {
  // 测试意图：这两条用例不需要 Daemon、模型或浏览器，必须注册为无 requires 的 L1，
  // 才能进入默认免费矩阵而不引入额外成本。
  for (const id of [RUNTIME_CASE_ID, IDE_CASE_ID]) {
    const caseDef = getCase(id)
    assert.ok(caseDef, `case ${id} should be found via getCase`)
    assert.ok(ALL_CASES.some((c) => c.id === id), `case ${id} should be in ALL_CASES`)
    assert.equal(caseDef.level, 'L1', `case ${id} level must be L1`)
    assert.deepEqual(Array.from(caseDef.requires), [], `case ${id} requires must be empty`)
    assert.ok(caseDef.docs.length > 0, `case ${id} must document its contract`)
  }
})

test('runtime absence case rejects with the canonical code and never fabricates a snapshot', async () => {
  // 测试意图：真实执行 runtime case，证明它读运行快照时拿到 409 unavailable + ENVIRONMENT_UNAVAILABLE，
  // 并断言响应里没有任何伪造的运行快照字段，最后按权威 version 清理环境。
  const { ctx, calls, isDeleted } = environmentWithoutDaemon()

  await getCase(RUNTIME_CASE_ID).run(ctx)

  const runtimeCall = calls.find((call) => call.path.endsWith('/runtime') && call.method === 'GET')
  assert.ok(runtimeCall, 'runtime case must read the runtime snapshot')
  assert.ok(
    runtimeCall.path.includes(ENVIRONMENT_ID),
    `runtime case must read its own environment: ${runtimeCall.path}`,
  )
  const invalid = calls.find((call) => call.path.includes('not-a-uuid'))
  assert.ok(invalid, 'runtime case must also reject a non-canonical environmentId')
  assert.ok(isDeleted(), 'runtime case must delete the environment it created')
})

test('IDE view case checks no-READY-owner rejection for canonical view commands', async () => {
  // 以 HTTP recorder 执行 IDE case，检查无 READY owner 时各命令的 409 unavailable + IDE_UNAVAILABLE 断言；
  // 不返回任何绑定结果，并且非 canonical environmentId 走 400 validation。
  const { ctx, calls, isDeleted } = environmentWithoutDaemon()

  await getCase(IDE_CASE_ID).run(ctx)

  const ideCalls = calls.filter((call) => call.path.includes('/api/ide/environments/'))
  assert.equal(ideCalls.length, 7, `IDE case must exercise seven IDE requests: ${JSON.stringify(ideCalls)}`)
  const viewsPath = `/api/ide/environments/${ENVIRONMENT_ID}/views`
  const open = ideCalls.find((call) => call.method === 'POST' && call.path === viewsPath)
  for (const field of ['viewId', 'documentId', 'eventNodeId', 'connectionId', 'claimId']) {
    assert.match(open.body[field], /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  }
  assert.equal(open.body.mode, 'create')
  assert.equal(
    ideCalls.filter((call) => call.method === 'POST' && call.path === viewsPath).length,
    1,
    'IDE case must open exactly one view on its own environment',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'PUT' && call.path.endsWith('/directory')),
    'IDE case must attempt a directory change',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'POST' && call.path.endsWith('/focus-terminal')),
    'IDE case must attempt a terminal focus',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'POST' && call.path.endsWith('/apply-mobile-layout')),
    'IDE case must attempt mobile layout',
  )
  assert.ok(
    calls.some((call) => call.method === 'GET' && call.path.endsWith('/directories')),
    'IDE case must attempt a directory listing',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'POST' && call.path.endsWith('/close')),
    'IDE case must attempt a close',
  )
  assert.ok(
    ideCalls.some((call) => call.path.includes('not-a-uuid')),
    'IDE case must also reject a non-canonical environmentId',
  )
  assert.ok(isDeleted(), 'IDE case must delete the environment it created')
})

test('environment fixture cleanup uses the authoritative version and tolerates an already deleted card', async () => {
  // 测试意图：清理必须先读回权威 version 再 CAS 删除；环境已经不存在（404）视为清理完成且不再发 DELETE；
  // 其它 canonical 错误必须原样抛出，不能被静默吞掉。
  const created = await createEnvironmentFixture(
    {
      async call(method, path) {
        assert.equal(method, 'POST')
        assert.equal(path, '/api/environments')
        return { json: { id: ENVIRONMENT_ID, version: '7' } }
      },
    },
    'e2e-fixture',
  )
  assert.equal(created.id, ENVIRONMENT_ID)
  assert.equal(created.version, '7')

  const deletes = []
  await deleteEnvironmentFixture(
    {
      async call(method, path) {
        if (method === 'GET') {
          return { json: { id: ENVIRONMENT_ID, version: '9' } }
        }
        deletes.push(path)
        return { json: null }
      },
    },
    ENVIRONMENT_ID,
  )
  assert.equal(deletes.length, 1)
  assert.ok(deletes[0].includes('expectedVersion=9'), `cleanup must use the read version: ${deletes[0]}`)

  let deleteCalls = 0
  await deleteEnvironmentFixture(
    {
      async call(method) {
        if (method === 'GET') {
          throw new HttpError(404, canonicalErrorBody(404, 'not_found', 'environment not found'), '/api/environments/missing')
        }
        deleteCalls++
        return { json: null }
      },
    },
    ENVIRONMENT_ID,
  )
  assert.equal(deleteCalls, 0, 'an already deleted environment must not be deleted again')

  await assert.rejects(
    () =>
      deleteEnvironmentFixture(
        {
          async call() {
            throw new HttpError(409, canonicalErrorBody(409, 'version_conflict', 'version conflict'), '/api/environments/conflict')
          },
        },
        ENVIRONMENT_ID,
      ),
    (error) => error instanceof HttpError && error.status === 409,
  )
})
