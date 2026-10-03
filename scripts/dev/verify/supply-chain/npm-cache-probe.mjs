import { mkdtemp, rm } from 'node:fs/promises'
import http from 'node:http'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'

// Synthetic principals only: never read npm config, credentials or the user's cache.
const authA = { authorization: 'Bearer synthetic-principal-a' }
const authB = { authorization: 'Bearer synthetic-principal-b', 'cache-control': 'max-stale=3600' }
const fresh = { 'cache-control': 'max-age=60' }
const cases = [
    { id: 'same-auth-fresh', kind: 'control', first: authA, second: authA, headers: fresh },
    { id: 'auth-change-no-vary', kind: 'observation', first: authA, second: authB, headers: fresh },
    { id: 'auth-vary', kind: 'protection', first: authA, second: authB,
        headers: { ...fresh, vary: 'Authorization' } },
    { id: 'cookie-vary', kind: 'protection',
        first: { cookie: 'session=synthetic-a' },
        second: { cookie: 'session=synthetic-b', 'cache-control': 'max-stale=3600' },
        headers: { ...fresh, vary: 'Cookie' } },
    { id: 'no-store', kind: 'protection', headers: { 'cache-control': 'no-store' },
        second: { 'cache-control': 'max-stale=3600' } },
    { id: 'no-cache-max-stale', kind: 'observation',
        headers: { 'cache-control': 'no-cache', age: '120' },
        second: { 'cache-control': 'max-stale=3600' } },
    { id: 'set-cookie-no-public', kind: 'header-observation',
        headers: { ...fresh, 'set-cookie': 'session=synthetic-response-a; Path=/' },
        second: { 'cache-control': 'max-stale=3600' } },
]

const report = {
    status: 'FAILED',
    runtime: {},
    assertions: { sharedFalse: false },
    cases: [],
    errors: [],
}
let temporaryDirectory
let server
const controller = new AbortController()
const deadline = setTimeout(() => controller.abort(), 30_000)

try {
    const npmRequire = createRequire('/usr/local/lib/node_modules/npm/package.json')
    const fetch = npmRequire('make-fetch-happen')
    const CachePolicy = npmRequire('make-fetch-happen/lib/cache/policy.js')
    const policy = new CachePolicy({
        request: { method: 'GET', url: 'http://127.0.0.1/probe', headers: new Map() },
        response: { status: 200, headers: new Map(Object.entries(fresh)) },
        options: {},
    })
    report.runtime = {
        node: process.version,
        npm: npmRequire('./package.json').version,
        makeFetchHappen: npmRequire('make-fetch-happen/package.json').version,
        httpCacheSemantics: npmRequire('http-cache-semantics/package.json').version,
        uid: process.getuid(),
        shared: policy.policy._isShared,
    }
    report.assertions.sharedFalse = report.runtime.shared === false
    if (!report.assertions.sharedFalse) {
        report.errors.push('runtime CachePolicy shared property is not false')
    }

    temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), 'kk-studio-npm-cache-probe-'))
    const states = new Map(cases.map(item => [item.id, { ...item, hits: 0 }]))
    server = http.createServer((request, response) => {
        const state = states.get(request.url.slice(1))
        if (!state) {
            response.writeHead(404).end()
            return
        }
        state.hits += 1
        response.writeHead(200, { 'content-type': 'text/plain', ...state.headers })
        response.end(`SYNTHETIC_BODY_${state.hits === 1 ? 'A' : 'B'}_${state.id}`)
    })
    await new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(0, '127.0.0.1', resolve)
    })

    for (const item of cases) {
        const url = `http://127.0.0.1:${server.address().port}/${item.id}`
        const options = {
            cachePath: path.join(temporaryDirectory, item.id),
            retry: 0,
            timeout: 5000,
            signal: controller.signal,
        }
        const first = await fetch(url, { ...options, headers: item.first ?? {} })
        // Consuming the body is required to finish make-fetch-happen's cache write.
        const firstBody = await first.text()
        const second = await fetch(url, { ...options, headers: item.second ?? {} })
        const secondBody = await second.text()
        const originHits = states.get(item.id).hits
        const source = secondBody === `SYNTHETIC_BODY_A_${item.id}` ? 'A'
            : secondBody === `SYNTHETIC_BODY_B_${item.id}` ? 'B' : 'UNKNOWN'
        const reused = originHits === 1 && source === 'A'
        const setCookie = second.headers.get('set-cookie')
        const result = {
            id: item.id,
            kind: item.kind,
            originHits,
            secondBody,
            source,
            cacheStatus: {
                first: first.headers.get('x-local-cache-status'),
                second: second.headers.get('x-local-cache-status'),
            },
            setCookie,
            setCookieReplayed: reused && setCookie !== null,
        }
        if (firstBody !== `SYNTHETIC_BODY_A_${item.id}` || source === 'UNKNOWN') {
            throw new Error(`unexpected synthetic body: ${item.id}`)
        }
        if (item.kind === 'control' || item.kind === 'protection') {
            const satisfied = item.kind === 'control'
                ? reused && result.cacheStatus.second === 'hit'
                : originHits === 2 && source === 'B'
            result.status = satisfied ? 'SATISFIED' : 'FAILED'
            if (!satisfied) {
                report.errors.push(`cache assertion failed: ${item.id}`)
            }
        } else {
            // Observations are not security PASSes and never assert reuse as desirable.
            result.status = (item.kind === 'observation' ? reused
                : reused && first.headers.has('set-cookie') && setCookie === null)
                ? 'OBSERVED' : 'NOT_OBSERVED'
        }
        report.cases.push(result)
    }
    if (report.errors.length === 0) {
        report.status = 'ASSERTIONS_SATISFIED'
    }
} catch (error) {
    report.errors.push(error.message)
} finally {
    clearTimeout(deadline)
    try {
        if (server?.listening) {
            server.closeAllConnections()
            await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
        }
    } catch (error) {
        report.errors.push(`server cleanup failed: ${error.message}`)
    } finally {
        if (temporaryDirectory) {
            try {
                await rm(temporaryDirectory, { recursive: true, force: true })
            } catch (error) {
                report.errors.push(`cache cleanup failed: ${error.message}`)
            }
        }
    }
}
if (report.errors.length > 0) {
    report.status = 'FAILED'
    process.exitCode = 1
    console.error(report.errors.join('\n'))
}
console.log(JSON.stringify(report, null, 2))
