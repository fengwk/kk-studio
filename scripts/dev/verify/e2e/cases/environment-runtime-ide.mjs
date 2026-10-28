import { assert, cid, expectHttpError } from '../lib/http.mjs'
import { createEnvironmentFixture, deleteEnvironmentFixture } from '../lib/fixtures.mjs'
import { registerCase } from '../lib/registry.mjs'

/**
 * IDE 视图身份：固定规范小写 UUID。
 *
 * <p>本组用例只验证“前置条件不满足时明确拒绝”的语义：既不建立真实浏览器会话，也不依赖任何 Daemon 或模型，
 * 因此可以在免费 L1 矩阵里稳定复现。
 */
const IDE_VIEW_ID = '11111111-1111-4111-8111-111111111111'
const IDE_DOCUMENT_ID = '22222222-2222-4222-8222-222222222222'
const IDE_EVENT_NODE_ID = '33333333-3333-4333-8333-333333333333'
const IDE_CONNECTION_ID = '44444444-4444-4444-8444-444444444444'
const IDE_CLAIM_ID = '55555555-5555-4555-8555-555555555555'

/**
 * canonical 错误信封断言：HTTP 状态、稳定机器码与“不得携带成功负载”必须同时成立。
 *
 * <p>只看状态码不足以证明契约：同一个 409 既可能是 IDE 通道不可用，也可能是其它领域冲突；机器码才是分支依据。
 */
function assertCanonicalError(error, { status, code }) {
  let body = null
  try {
    body = JSON.parse(error.body)
  } catch {
    body = null
  }
  assert(body && typeof body === 'object', `error body must be a JSON envelope: ${error.body}`)
  assert(body.status === status, `error status must be ${status}: ${error.body}`)
  assert(body.code === code, `error code must be ${code}: ${error.body}`)
  assert(body.data == null, `error must not carry a success payload: ${error.body}`)
  return body
}

registerCase({
  id: 'environment.runtime_absent_unavailable',
  level: 'L1',
  title: 'Environment 运行快照缺失时明确 unavailable，不伪造空快照',
  docs: 'GET /api/environments/{id}/runtime 在没有本代 READY 快照（无 READY owner 路由）时返回 409 + canonical code environment_unavailable，响应不含 revision/status/daemonVersion/update 任何运行快照字段，绝不伪造 update=null 的假快照；非 canonical environmentId 返回 400 validation；用例创建的 Environment 在结束时按权威 version 清理',
  async run(ctx) {
    const card = await createEnvironmentFixture(ctx, `e2e-runtime-absent-${cid().slice(0, 8)}`)

    try {
      const error = await expectHttpError(
        () => ctx.call('GET', `/api/environments/${encodeURIComponent(card.id)}/runtime`),
        { status: 409 },
      )
      assertCanonicalError(error, { status: 409, code: 'environment_unavailable' })
      assert(
        !/"revision"\s*:/.test(error.body),
        `runtime absence must not fabricate a revision: ${error.body}`,
      )
      assert(
        !/"daemonVersion"\s*:/.test(error.body),
        `runtime absence must not fabricate a daemon version: ${error.body}`,
      )
      assert(
        !/"update"\s*:/.test(error.body),
        `runtime absence must not fabricate an update slot: ${error.body}`,
      )

      // 非 canonical environmentId 在进入服务之前即被拒绝，不落到运行快照读取。
      const invalid = await expectHttpError(
        () => ctx.call('GET', '/api/environments/not-a-uuid/runtime'),
        { status: 400 },
      )
      assertCanonicalError(invalid, { status: 400, code: 'validation' })
    } finally {
      await deleteEnvironmentFixture(ctx, card.id)
    }
  },
})

registerCase({
  id: 'environment.ide_view_exact_intent_rejected',
  level: 'L1',
  title: 'IDE 视图在缺少精确浏览器意图时被明确拒绝，不伪造绑定',
  docs: 'POST /api/environments/{id}/ide/views 必须先经事件节点核对精确浏览器意图（environment/view/claim 全部匹配）；没有 READY owner 或精确意图时返回 409 + canonical code ide_unavailable，响应不含 entryUrl/bindingEpoch 等绑定结果，绝不把没有的视图当作已打开；PUT 目录、POST 终端、DELETE 关闭同样返回 409 ide_unavailable；非 canonical environmentId 返回 400 validation；用例创建的 Environment 在结束时按权威 version 清理',
  async run(ctx) {
    const card = await createEnvironmentFixture(ctx, `e2e-ide-intent-${cid().slice(0, 8)}`)
    const viewsPath = `/api/environments/${encodeURIComponent(card.id)}/ide/views`
    const openBody = {
      viewId: IDE_VIEW_ID,
      documentId: IDE_DOCUMENT_ID,
      eventNodeId: IDE_EVENT_NODE_ID,
      connectionId: IDE_CONNECTION_ID,
      claimId: IDE_CLAIM_ID,
      mode: 'create',
      directory: '/workspace',
    }

    try {
      const openError = await expectHttpError(
        () => ctx.call('POST', viewsPath, openBody),
        { status: 409 },
      )
      assertCanonicalError(openError, { status: 409, code: 'ide_unavailable' })
      assert(
        !/"entryUrl"\s*:/.test(openError.body) && !/"bindingEpoch"\s*:/.test(openError.body),
        `rejected open must not fabricate a view binding: ${openError.body}`,
      )

      // 同一前置条件覆盖同一视图的其它命令：目录切换、终端聚焦与关闭都不得在没有精确意图时成功。
      const directoryError = await expectHttpError(
        () =>
          ctx.call('PUT', `${viewsPath}/${IDE_VIEW_ID}/directory`, {
            documentId: IDE_DOCUMENT_ID,
            bindingEpoch: '1',
            directory: '/workspace/subdir',
          }),
        { status: 409 },
      )
      assertCanonicalError(directoryError, { status: 409, code: 'ide_unavailable' })

      const terminalError = await expectHttpError(
        () =>
          ctx.call('POST', `${viewsPath}/${IDE_VIEW_ID}/terminal`, {
            documentId: IDE_DOCUMENT_ID,
            bindingEpoch: '1',
          }),
        { status: 409 },
      )
      assertCanonicalError(terminalError, { status: 409, code: 'ide_unavailable' })

      const closeError = await expectHttpError(
        () =>
          ctx.call('DELETE', `${viewsPath}/${IDE_VIEW_ID}`, {
            documentId: IDE_DOCUMENT_ID,
            bindingEpoch: '1',
            discard: true,
          }),
        { status: 409 },
      )
      assertCanonicalError(closeError, { status: 409, code: 'ide_unavailable' })

      // 非 canonical environmentId 在进入路由判定之前即被拒绝。
      const invalid = await expectHttpError(
        () => ctx.call('POST', '/api/environments/not-a-uuid/ide/views', openBody),
        { status: 400 },
      )
      assertCanonicalError(invalid, { status: 400, code: 'validation' })
    } finally {
      await deleteEnvironmentFixture(ctx, card.id)
    }
  },
})
