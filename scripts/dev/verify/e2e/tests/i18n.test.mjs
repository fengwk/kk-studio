import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import test from 'node:test'

import { HttpError } from '../lib/http.mjs'
import { getCase } from '../run-matrix.mjs'

const fixtures = JSON.parse(readFileSync(new URL('./resources/i18n-errors.json', import.meta.url), 'utf8'))
const caseDef = getCase('i18n.error_response_accept_language')

async function withResponses(mutate, run) {
  const requests = []
  const server = createServer((req, res) => {
    const language = req.headers['accept-language'] === 'zh-CN' ? 'zh-CN' : 'en-US'
    const kind = req.url.startsWith('/api/chat-spaces/')
      ? 'domain'
      : req.url.startsWith('/api/platform/model-catalog?')
        ? 'validation'
        : 'responseStatus'
    const fixture = fixtures[kind]
    const body = {
      type: 'about:blank',
      title: fixture.messages[language],
      status: fixture.status,
      code: fixture.code,
      reason: fixture.reason,
      detail: fixture.detail,
    }
    requests.push({ path: req.url, language: req.headers['accept-language'] })
    mutate(body, kind, language)
    res.writeHead(fixture.status, { 'Content-Type': 'application/problem+json' })
    res.end(JSON.stringify(body))
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    const baseUrl = `http://127.0.0.1:${server.address().port}`
    await run(
      {
        baseUrl,
        request: async (requestPath, { headers } = {}) => {
          const response = await fetch(`${baseUrl}${requestPath}`, { headers })
          const body = await response.text()
          if (!response.ok) throw new HttpError(response.status, body, requestPath)
          return body ? JSON.parse(body) : null
        },
      },
      requests,
    )
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
    assert.deepEqual(requests.map((request) => request.path), [
      '/api/chat-spaces/00000000-0000-0000-0000-000000000999/tree',
      '/api/chat-spaces/00000000-0000-0000-0000-000000000999/tree',
      '/api/platform/model-catalog?limit=abc',
      '/api/platform/model-catalog?limit=abc',
      '/api/platform/model-catalog?limit=abc',
      '/api/harness/threads/not-a-number',
      '/api/harness/threads/not-a-number',
      '/api/harness/threads/not-a-number',
    ])
    assert.deepEqual(requests.map((request) => request.language), [
      'en-US', 'zh-CN', 'en-US', 'zh-CN', 'fr-FR', 'en-US', 'zh-CN', 'fr-FR',
    ])
  })
})

test('localized error case rejects a changed stable code', async () => {
  // 负对照确保本地化不会改变机器类别。
  await withResponses((body, kind, language) => {
    if (kind === 'domain' && language === 'zh-CN') body.code = 'validation'
  }, (ctx) => assert.rejects(() => caseDef.run(ctx), /"code":"validation"/))
})
