import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { REAL_MODEL_DEFINITIONS } from '../lib/real-models.mjs'

const execute = promisify(execFile)
const model = REAL_MODEL_DEFINITIONS.find(({ idSuffix }) => idSuffix === 'openai_responses')
const usage = JSON.parse(readFileSync(new URL('./resources/text-cache/usage.json', import.meta.url))).miss
const apiCase = 'real.text_cache.openai_responses'
const uiCase = 'ui.environments.page_loads'
const sentinel = '普通回显 <sample> "quoted" | unmarked\nsecond line'

async function fixture(t, { failLogin = false } = {}) {
  const directory = mkdtempSync(path.join(tmpdir(), 'e2e-results-only-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const [spaceId, sessionId, threadId, commandId] = Array.from({ length: 4 }, () => randomUUID())
  const threadPath = `/api/harness/threads/${threadId}`
  const requests = []
  const errors = []
  let baseUrl
  let entries
  const server = createServer(async (request, response) => {
    try {
      const { url, method } = request
      requests.push(`${method} ${url}`)
      const json = (value) => {
        response.setHeader('Content-Type', 'application/json')
        response.end(JSON.stringify(value))
      }
      const body = async () => {
        const chunks = []
        for await (const chunk of request) chunks.push(chunk)
        return JSON.parse(Buffer.concat(chunks))
      }
      const echo = () => {
        response.writeHead(502, { 'Content-Type': 'text/plain' })
        response.end(sentinel)
      }
      if (url === '/api/auth/login') {
        if (failLogin) return echo()
        response.setHeader('Set-Cookie', 'studio_session=fixture; HttpOnly')
        return json({})
      }
      if (url === '/api/platform/providers') return json([{
        name: model.providerName, providerType: model.providerType, baseUrl, credential: { configured: true },
      }])
      if (url === '/api/platform/models') return json([{
        name: model.modelName, providerName: model.providerName,
        config: { variants: model.variants.map((id) => ({ id })) },
      }])
      if (url === '/api/platform/agents' && method === 'POST') {
        const { name, execution } = await body()
        assert.deepEqual(execution.settings, { modelName: model.modelName, variant: model.variant })
        return json({ name, execution, version: '0' })
      }
      if (url === '/api/chat-spaces' && method === 'POST') return json({ id: spaceId })
      if (url === `/api/chat-spaces/${spaceId}/sessions` && method === 'POST') {
        const { message, modelSelection } = await body()
        assert.equal(modelSelection.modelName, model.modelName)
        const text = message[0].text
        const entry = (entryType, payload) => ({ entryId: randomUUID(), entryType, payload })
        entries = [
          entry('TURN_START', {}),
          entry('MESSAGE', { message: { role: 'USER', contents: [{ type: 'text', text }] } }),
          entry('MESSAGE', {
            message: { role: 'ASSISTANT', contents: [{ type: 'text', text: text.match(/MARKER-[\w-]+/)[0] }] },
            assistantMetadata: { usage },
          }),
          entry('TURN_END', { outcome: 'COMPLETED', continueModel: false }),
        ]
        return json({ sessionId, threadId, commandId, treeVersion: '1' })
      }
      if (url === threadPath) return json({
        thread: { threadId, sessionId, headEntryId: entries.at(-1).entryId, name: 'fixture', status: 'IDLE',
          executionControl: 'RUNNABLE', yoloEnabled: false, rootThreadId: threadId, parentThreadId: null,
          nextCommandSequence: '2', version: '1' },
        entries, activeAssistantOutput: null,
      })
      if (url === '/mock-provider/responses') return echo()
      if (url === `${threadPath}/command-batches`) {
        const upstream = await fetch(`${baseUrl}/mock-provider/responses`)
        response.writeHead(upstream.status, { 'Content-Type': 'text/plain' })
        return response.end(await upstream.text())
      }
      if (url === `/api/chat-spaces/${spaceId}/tree`) return json({ treeVersion: '1' })
      if (method === 'DELETE') return json({})
      throw new Error(`Unexpected fixture request: ${method} ${url}`)
    } catch (error) {
      errors.push(error)
      response.writeHead(500).end()
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise((resolve) => server.close(resolve)))
  baseUrl = `http://127.0.0.1:${server.address().port}`
  return { directory, baseUrl, requests, errors, threadPath }
}

function run(script, argv, nodeArgs = []) {
  return execute(process.execPath, [...nodeArgs, fileURLToPath(new URL(`../${script}`, import.meta.url)),
    '--real-model', model.idSuffix, ...argv], { env: {}, timeout: 15000 }).then(
    (result) => ({ ...result, code: 0 }), (error) => error,
  )
}

function assertResults(directory, id, summary, report) {
  const resultFile = `cases/${id}.json`
  const files = readdirSync(directory, { recursive: true }).sort()
  assert.deepEqual(files, ['cases', resultFile, report, summary].sort())
  const result = JSON.parse(readFileSync(path.join(directory, resultFile), 'utf8'))
  assert.deepEqual(result, { id, level: id.startsWith('ui.') ? 'L5' : 'L2', status: 'fail', durationMs: result.durationMs })
  assert.ok(Number.isFinite(result.durationMs))
  const summaryText = readFileSync(path.join(directory, summary), 'utf8')
  assert.equal(JSON.parse(summaryText).totals.fail, 1)
  assert.deepEqual(JSON.parse(summaryText).results, [result])
  assert.equal(summaryText.includes('baseUrl'), false)
  const markdown = readFileSync(path.join(directory, report), 'utf8')
  assert.ok(markdown.includes(`| FAIL | \`${id}\` |`))
  assert.equal(markdown.includes('quoted'), false)
}

test('strict API CLI omits successful round artifacts and a synthetic upstream error', async (t) => {
  // The first round writes evidence in ordinary mode; the follow-up fails with unlabelled upstream text.
  const { directory, baseUrl, requests, errors, threadPath } = await fixture(t)
  const args = ['--base-url', baseUrl, '--only', apiCase, '--report-root', directory]
  const strict = await run('run-matrix.mjs', [...args, '--results-only'])
  assert.equal(strict.code, 1, strict.stderr)
  assert.equal(strict.stderr, `FAIL ${apiCase}\n`)
  assert.equal(strict.stdout.includes('quoted'), false)
  assert.equal(requests[0], 'POST /api/auth/login')
  assert.ok(requests.includes(`POST ${threadPath}/command-batches`), JSON.stringify(requests))
  assert.ok(requests.includes('GET /mock-provider/responses'))
  assert.deepEqual(errors, [])
  const latest = path.join(directory, 'latest')
  assertResults(latest, apiCase, 'summary.json', 'report.md')
  const runDir = readFileSync(path.join(directory, 'LATEST_RUN.txt'), 'utf8').split('\n')[0]
  assertResults(runDir, apiCase, 'summary.json', 'report.md')
  const ordinary = await run('run-matrix.mjs', args)
  assert.equal(ordinary.code, 1)
  assert.match(ordinary.stderr, /quoted/)
  const artifacts = path.join(latest, 'artifacts/real_text_cache_openai_responses')
  assert.deepEqual(readdirSync(artifacts).sort(), ['error.txt', 'real-text-cache-openai_responses.json'])
  assert.match(readFileSync(path.join(latest, 'report.md'), 'utf8'), /quoted/)
})

test('strict UI CLI skips both regular and failure captures and keeps only the fixed case outcome', async (t) => {
  // The controlled browser renders the loopback echo and feeds it to both error listeners.
  const { directory, baseUrl, requests, errors } = await fixture(t)
  const preload = fileURLToPath(new URL('./resources/results-only-browser.mjs', import.meta.url))
  const args = ['--base-url', baseUrl, '--backend-url', baseUrl, '--only', uiCase, '--report-dir', directory]
  const strict = await run('ui-smoke.mjs', [...args, '--results-only'], ['--import', preload])
  assert.equal(strict.code, 1, strict.stderr)
  assert.equal(strict.stderr, `FAIL ${uiCase}\n`)
  assert.equal(strict.stdout.includes('quoted'), false)
  assert.deepEqual(requests.slice(0, 4), [
    'POST /api/auth/login', 'GET /api/platform/models', 'GET /api/platform/providers', 'GET /mock-provider/responses',
  ])
  assert.deepEqual(errors, [])
  assertResults(directory, uiCase, 'ui-summary.json', 'ui-report.md')
  const ordinary = await run('ui-smoke.mjs', args, ['--import', preload])
  assert.equal(ordinary.code, 1)
  assert.match(ordinary.stderr, /quoted/)
  const artifacts = path.join(directory, 'artifacts/ui_environments_page_loads')
  assert.deepEqual(readdirSync(artifacts).sort(), [
    'error.txt', 'failure-state.json', 'failure.png', 'settings-environments-manage.png', 'settings-environments.png',
  ])
  const failureState = JSON.parse(readFileSync(path.join(artifacts, 'failure-state.json'), 'utf8'))
  assert.equal(failureState.bodyText, sentinel)
  assert.ok(failureState.pageErrors[0].includes(sentinel))
  assert.ok(failureState.consoleErrors[0].includes(sentinel))
})

test('strict top-level failures keep exit 2 and omit the HTTP login body', async (t) => {
  const { directory, baseUrl, requests } = await fixture(t, { failLogin: true })
  for (const [script, args, message, nodeArgs] of [
    ['run-matrix.mjs', ['--base-url', baseUrl, '--only', apiCase, '--report-root', directory], 'matrix', []],
    ['ui-smoke.mjs', ['--backend-url', baseUrl, '--report-dir', directory], 'UI',
      ['--import', fileURLToPath(new URL('./resources/results-only-browser.mjs', import.meta.url))]],
  ]) {
    const result = await run(script, [...args, '--results-only'], nodeArgs)
    assert.equal(result.code, 2)
    assert.equal(result.stderr, `E2E ${message} could not complete\n`)
    assert.equal(result.stdout, '')
  }
  assert.deepEqual(requests, ['POST /api/auth/login', 'POST /api/auth/login'])
  assert.deepEqual(readdirSync(directory), [])
})
