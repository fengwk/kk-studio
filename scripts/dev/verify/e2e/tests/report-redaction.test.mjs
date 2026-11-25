import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { promisify } from 'node:util'
import { writeReportArtifact } from '../lib/redact.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'

const execute = promisify(execFile)

function temporaryReport(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'e2e-redaction-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  return directory
}

test('report JSON masks nested strings before encoding and preserves numeric facts and binary bytes', (t) => {
  // 合成值不应进入制品；JSON 内的错误正文必须在引号转义前脱敏。
  const directory = temporaryReport(t)
  const secret = randomUUID()
  const file = path.join(directory, 'diagnostic.json')
  writeReportArtifact(file, JSON.stringify({
    error: `upstream body: ${JSON.stringify({ API_KEY: `prefix\"${secret}`, Authorization: `Bearer ${secret}` })}`,
    nested: [{ apiKey: secret, Authorization: `Bearer ${secret}`, password: secret, inputTokens: 42, configured: false }],
  }))
  const text = readFileSync(file, 'utf8')
  assert.equal(text.includes(secret), false, 'JSON must not expose credential values')
  const value = JSON.parse(text)
  assert.deepEqual(value.nested, [{ apiKey: '[REDACTED]', Authorization: '[REDACTED]', password: '[REDACTED]', inputTokens: 42, configured: false }])
  assert.match(value.error, /\[REDACTED\]/)

  const binary = Uint8Array.from([0, 255, 3, 128])
  const binaryFile = path.join(directory, 'screenshot.bin')
  writeReportArtifact(binaryFile, binary)
  assert.deepEqual(readFileSync(binaryFile), Buffer.from(binary))
  const textFile = path.join(directory, 'error.txt')
  writeReportArtifact(textFile, `Authorization: Bearer ${secret}`)
  assert.equal(readFileSync(textFile, 'utf8'), 'Authorization: [REDACTED]')
})

test('the real API runner redacts failures in cases, summary, markdown, error artifacts and stderr', async (t) => {
  // 真实 CLI 对隔离 HTTP 夹具发请求，验证实际报告链，不替换用例或写盘入口。
  const directory = temporaryReport(t)
  const secrets = Array.from({ length: 3 }, () => randomUUID())
  const requests = []
  const server = createServer((request, response) => {
    requests.push(request.url)
    if (request.url === '/api/auth/login') {
      response.writeHead(200, {
        'Content-Type': 'application/json',
        'Set-Cookie': 'studio_session=fixture; HttpOnly',
      })
      response.end('{}')
      return
    }
    if (request.url === '/api/platform/providers') {
      response.setHeader('Content-Type', 'application/json')
      response.end(JSON.stringify([{ name: 'unconfigured', credential: { configured: false }, baseUrl: null }]))
      return
    }
    response.writeHead(500, { 'Content-Type': 'text/plain' })
    response.end(`Authorization: Bearer ${secrets[0]}\n${JSON.stringify({ API_KEY: secrets[1], password: secrets[2] })}`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise((resolve) => server.close(resolve)))
  const { port } = server.address()
  const outcome = await execute(process.execPath, [
    path.join(REPO_ROOT, 'scripts/dev/verify/e2e/run-matrix.mjs'),
    '--base-url', `http://127.0.0.1:${port}`,
    '--only', 'seed.structured_model_config',
    '--report-root', directory,
  ], { timeout: 15000 }).then(
    (result) => ({ ...result, code: 0 }),
    (error) => error,
  )
  assert.equal(outcome.code, 1, 'the failed HTTP case must remain a failure')
  assert.deepEqual(requests, ['/api/auth/login', '/api/platform/providers', '/api/platform/models'])
  const latest = path.join(directory, 'latest')
  for (const relative of [
    'cases/seed.structured_model_config.json',
    'summary.json',
    'report.md',
    'artifacts/seed_structured_model_config/error.txt',
  ]) {
    const contents = readFileSync(path.join(latest, relative), 'utf8')
    for (const secret of secrets) {
      assert.equal(contents.includes(secret), false, `${relative} exposed a credential`)
    }
    assert.match(contents, /\[REDACTED\]/)
    if (relative.endsWith('.json')) JSON.parse(contents)
  }
  for (const secret of secrets) {
    assert.equal(outcome.stderr.includes(secret), false, 'stderr exposed a credential')
  }
  assert.equal(JSON.parse(readFileSync(path.join(latest, 'summary.json'), 'utf8')).totals.fail, 1)
})
