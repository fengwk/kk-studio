import { assert, cid, envelopeData } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'environment.skill_source_operations',
  level: 'L1',
  title: 'Environment Skill 来源 CRUD、持久清单与异步操作取消生命周期',
  docs: 'POST 创建环境与 Git 来源 (example.invalid)，GET 清单，PUT CAS 更新来源，POST 提交 install 返回 202 PENDING，GET 操作列表与详情，POST cancel 同步取消返回 CANCELLED，最后使用 PUT 返回版本 CAS 安全删除来源与环境',
  async run(ctx) {
    let envId = null
    let sourceId = null

    try {
      // 1. 创建 Environment（不记录或泄露 registrationToken）
      const envRes = await ctx.call('POST', '/api/harness/environments', {
        name: `e2e-env-${cid().slice(0, 8)}`,
      })
      assert(envRes.status === 201, `expected 201 for env create, got ${envRes.status}`)
      const envData = envelopeData(envRes.json)
      envId = envData.id
      assert(envId, 'created environment must have id')

      // 2. 注册 Git Skill 来源（使用非凭据 example.invalid 规范地址）
      const srcRes = await ctx.call(
        'POST',
        `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources`,
        {
          type: 'git',
          gitUrl: 'https://example.invalid/test-skills.git',
        },
      )
      assert(srcRes.status === 201, `expected 201 for source create, got ${srcRes.status}`)
      const srcData = envelopeData(srcRes.json)
      sourceId = srcData.sourceId
      assert(sourceId, 'created source must have sourceId')
      assert(srcData.type === 'git', `expected git source, got ${srcData.type}`)
      assert(srcData.status === 'UNAPPLIED', `expected UNAPPLIED, got ${srcData.status}`)
      let currentSourceVersion = srcData.version

      // 3. 读取来源列表与持久清单，断言 HTTP 状态码与默认来源满足性
      const listRes = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources`,
      )
      assert(listRes.status === 200, `expected 200 for skill sources list, got ${listRes.status}`)
      const sources = envelopeData(listRes.json)
      assert(Array.isArray(sources) && sources.length >= 2, 'expected at least 2 sources')
      const defaultSources = sources.filter((s) => s.defaultSource === true)
      assert(
        defaultSources.length === 1,
        `expected exactly one default source, found ${defaultSources.length}`,
      )

      const invRes = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/inventory`,
      )
      assert(invRes.status === 200, `expected 200 for inventory, got ${invRes.status}`)
      const invData = envelopeData(invRes.json)
      assert(invData.environmentId === envId, 'inventory must match environmentId')

      const skillsRes = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/inventory/skills?usableOnly=false`,
      )
      assert(skillsRes.status === 200, `expected 200 for inventory skills, got ${skillsRes.status}`)
      const skills = envelopeData(skillsRes.json)
      assert(Array.isArray(skills), 'inventory skills must be array')

      // 4. PUT-update custom GIT source with CAS before INSTALL
      const putRes = await ctx.call(
        'PUT',
        `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources/${encodeURIComponent(sourceId)}`,
        {
          type: 'git',
          gitUrl: 'https://example.invalid/test-skills.git',
          gitRef: 'develop',
          expectedVersion: currentSourceVersion,
        },
      )
      assert(putRes.status === 200, `expected 200 for source PUT update, got ${putRes.status}`)
      const updatedSrc = envelopeData(putRes.json)
      assert(
        BigInt(updatedSrc.version) > BigInt(currentSourceVersion),
        'expected numerical version increment after PUT',
      )
      assert(updatedSrc.gitRef === 'develop', `expected gitRef develop, got ${updatedSrc.gitRef}`)
      currentSourceVersion = updatedSrc.version

      // 5. 提交异步 INSTALL 操作，断言 202 与 PENDING
      const opCreateRes = await ctx.call(
        'POST',
        `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources/${encodeURIComponent(sourceId)}/install`,
        { timeoutMillis: 5000 },
      )
      assert(opCreateRes.status === 202, `expected 202 for install, got ${opCreateRes.status}`)
      const opData = envelopeData(opCreateRes.json)
      const opId = opData.id
      assert(opId, 'operation must have id')
      assert(
        opData.operationType === 'SKILL_INSTALL',
        `expected SKILL_INSTALL, got ${opData.operationType}`,
      )
      assert(opData.status === 'PENDING', `expected PENDING, got ${opData.status}`)

      // 6. 查询操作详情与列表，确认均包含操作且状态为 PENDING
      const opGetRes = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/operations/${encodeURIComponent(opId)}`,
      )
      assert(opGetRes.status === 200, `expected 200 for operation detail, got ${opGetRes.status}`)
      const opGetData = envelopeData(opGetRes.json)
      assert(opGetData.status === 'PENDING', `expected PENDING on GET, got ${opGetData.status}`)

      const opListRes = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/operations?limit=50`,
      )
      assert(opListRes.status === 200, `expected 200 for operations list, got ${opListRes.status}`)
      const opList = envelopeData(opListRes.json)
      assert(Array.isArray(opList), 'operations list must be array')
      const foundOp = opList.find((o) => o.id === opId)
      assert(foundOp, `operations list must contain operation ${opId}`)
      assert(foundOp.status === 'PENDING', `expected PENDING in list, got ${foundOp.status}`)

      // 7. 取消操作，断言 200 与 CANCELLED
      const cancelRes = await ctx.call(
        'POST',
        `/api/harness/environments/${encodeURIComponent(envId)}/operations/${encodeURIComponent(opId)}/cancel`,
      )
      assert(cancelRes.status === 200, `expected 200 for cancel, got ${cancelRes.status}`)
      const cancelledData = envelopeData(cancelRes.json)
      assert(cancelledData.status === 'CANCELLED', `expected CANCELLED, got ${cancelledData.status}`)

      // 8. 使用 PUT 返回的版本进行 CAS 安全删除自定义 Skill 来源，断言 204
      const delRes = await ctx.call(
        'DELETE',
        `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources/${encodeURIComponent(sourceId)}?expectedVersion=${encodeURIComponent(currentSourceVersion)}`,
      )
      assert(delRes.status === 204, `expected 204 for source delete, got ${delRes.status}`)
      sourceId = null

      const postDelRes = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources/${encodeURIComponent(srcData.sourceId)}`,
      )
      assert(postDelRes.status === 404, `expected 404 after source delete, got ${postDelRes.status}`)
    } finally {
      if (envId && sourceId) {
        try {
          const sRes = await ctx.call(
            'GET',
            `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources/${encodeURIComponent(sourceId)}`,
          )
          if (sRes.status === 200) {
            const s = envelopeData(sRes.json)
            await ctx.call(
              'DELETE',
              `/api/harness/environments/${encodeURIComponent(envId)}/skill-sources/${encodeURIComponent(sourceId)}?expectedVersion=${encodeURIComponent(s.version)}`,
            )
          }
        } catch {}
      }
      if (envId) {
        try {
          const eRes = await ctx.call(
            'GET',
            `/api/harness/environments/${encodeURIComponent(envId)}`,
          )
          if (eRes.status === 200) {
            const e = envelopeData(eRes.json)
            await ctx.call(
              'DELETE',
              `/api/harness/environments/${encodeURIComponent(envId)}?expectedVersion=${encodeURIComponent(e.version)}`,
            )
          }
        } catch {}
      }
    }
  },
})
