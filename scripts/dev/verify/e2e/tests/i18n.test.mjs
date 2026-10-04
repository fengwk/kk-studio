import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import test from 'node:test'

import '../cases/i18n.mjs'
import { getCase } from '../lib/registry.mjs'

const fixtures = JSON.parse(readFileSync(new URL('./resources/i18n-errors.json', import.meta.url), 'utf8'))
const caseDef = getCase('i18n.error_response_accept_language')

async function withResponses(mutate, run) {
  const requests = []
  const server = createServer((req, res) => {
    const language = req.headers['accept-language'] === 'zh-CN' ? 'zh-CN' : 'en-US'
    const kind = req.url.startsWith('/api/ai/chats/')
      ? 'domain'
      : req.url.startsWith('/api/ai/catalog/models?')
        ? 'validation'
        : 'responseStatus'
    const fixture = fixtures[kind]
    const body = {
      code: fixture.code,
      message: fixture.messages[language],
      errors: { ...fixture.errors, ...(fixture.titles ? { title: fixture.titles[language] } : {}) },
    }
    requests.push({ path: req.url, language: req.headers['accept-language'] })
    mutate(body, kind, language)
    res.writeHead(fixture.status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    await run({ baseUrl: `http://127.0.0.1:${server.address().port}` }, requests)
  } finally {
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  }
}

test('localized error case checks Chinese display text, stable codes and language fallback', async () => {
  // 用 loopback 夹具验证矩阵断言本身；实际后端矩阵仍是独立验收。
  await withResponses(() => {}, async (ctx, requests) => {
    await caseDef.run(ctx)
    assert.equal(requests.length, 8)
    assert.deepEqual(requests.map((request) => request.language), [
      'en-US', 'zh-CN', 'en-US', 'zh-CN', 'fr-FR', 'en-US', 'zh-CN', 'fr-FR',
    ])
  })
})

test('localized error case rejects old display text and translated machine resource ids', async () => {
  // 负对照确保不会沿用旧展示名，或把机器资源标识一起翻译。
  for (const [field, value, expected] of [
    ['message', '未找到 chat。', /未找到 chat/],
    ['resource', '对话', /"resource":"对话"/],
  ]) {
    await withResponses((body, kind, language) => {
      if (kind === 'domain' && language === 'zh-CN') {
        if (field === 'message') body.message = value
        else body.errors.resource = value
      }
    }, (ctx) => assert.rejects(() => caseDef.run(ctx), expected))
  }
})
