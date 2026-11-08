/** Permanent guards for the reliability stack's Node daemon bootstrap and token boundary. */

import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { CARD_ID, runProcess, startCardServer } from '../../tests/support/card-server.mjs'
import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const RELIABILITY_COMPOSE = path.join(REPOSITORY_ROOT, 'deploy/test/compose.yaml')
const RELIABILITY_ENTRYPOINT = path.join(REPOSITORY_ROOT, 'deploy/reliability/daemon-entrypoint.sh')
const RELIABILITY_DOCKERFILE = path.join(REPOSITORY_ROOT, 'deploy/reliability/daemon.Dockerfile')

const DAEMON_CONFIG = {
  studioUrl: 'http://127.0.0.1:8080',
  bashExecutable: null,
  lsp: null,
  proxyUrl: null,
}

const mode = (file) => statSync(file).mode & 0o777
const text = (file) => readFileSync(file, 'utf8')
const count = (haystack, needle) => haystack.split(needle).length - 1

async function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-entrypoint-'))
  try {
    return await body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/**
 * 按发行布局写一个最小 Daemon 目录：根目录入口 + 冻结运行时。
 *
 * 默认运行时是一个可执行包装脚本：配置 helper 交给真实 Node，其它调用被记录成 daemon 启动
 * 参数，因此断言的是入口真实的 exec 行为而不是静态文本。
 */
function writeNodeDist(temporary, { realNode = false } = {}) {
  const dist = path.join(temporary, 'daemon-dist')
  mkdirSync(path.join(dist, 'runtime/code-server/lib'), { recursive: true })
  writeFileSync(path.join(dist, 'main.js'), '// daemon entry\n')
  const runtime = path.join(dist, 'runtime/code-server/lib/node')
  const argvFile = path.join(temporary, 'argv.txt')
  const environFile = path.join(temporary, 'environ.txt')
  writeFileSync(
    runtime,
    realNode
      ? `#!/bin/bash\nexec '${process.execPath}' "$@"\n`
      : '#!/bin/bash\n' +
          `if [ "$1" = "-e" ] || [[ "$1" == *"/daemon-config-cache.mjs" ]]; then exec '${process.execPath}' "$@"; fi\n` +
          'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n' +
          'env >"$PROBE_ENVIRON"\n',
  )
  chmodSync(runtime, 0o755)
  return { dist, argvFile, environFile }
}

function runEntrypoint(env, { args = [RELIABILITY_ENTRYPOINT] } = {}) {
  return runProcess('bash', args, { env, timeoutMillis: 30_000 })
}

/** Remove ambient daemon inputs so a test cannot pass due to the invoking shell. */
function baseEnv(overrides = {}) {
  const environment = { ...process.env }
  for (const name of Object.keys(environment)) {
    if (name.startsWith('KK_STUDIO_DAEMON_')) {
      delete environment[name]
    }
  }
  return { ...environment, ...overrides }
}

test('test intent: compose injects only environment; the entrypoint materializes config and token', () => {
  const compose = text(RELIABILITY_COMPOSE)

  assert.ok(compose.includes('KK_STUDIO_DAEMON_REGISTRATION_TOKEN'))
  assert.ok(compose.includes('KK_STUDIO_DAEMON_STUDIO_URL: http://app:8080'))
  assert.ok(compose.includes('KK_STUDIO_DAEMON_ENVIRONMENT_NAME: ${RELIABILITY_ENV_NAME:-docker-reliability}'))
  assert.ok(!compose.includes('KK_STUDIO_DAEMON_NOTE'))
  assert.ok(compose.includes('e2e-token-reliability'))
  // daemon 服务保留：镜像/uid/workspace named volume/健康依赖是现存自动化基础设施。
  assert.ok(compose.includes('dockerfile: deploy/reliability/daemon.Dockerfile'))
  assert.ok(compose.includes('user: "0:0"'))
  assert.ok(compose.includes('daemon-workspace:/workspace'))
})

test('test intent: the daemon image runs the frozen Node distribution only', () => {
  // Daemon 镜像只带冻结 Node 发行目录：没有 JRE/Maven，也没有第二个 Node。
  const dockerfile = text(RELIABILITY_DOCKERFILE)

  assert.ok(dockerfile.includes('daemon-entrypoint.sh'))
  assert.equal(count(dockerfile, 'deploy/reliability/daemon-entrypoint.sh'), 1, '入口脚本只能被 COPY 一次')
  assert.equal(
    count(dockerfile, 'deploy/reliability/daemon-config-cache.mjs'),
    1,
    '配置 helper 只能被 COPY 一次',
  )
  assert.ok(dockerfile.includes('/usr/local/bin/daemon-config-cache.mjs'))
  // 发行目录由真实打包脚本产出，运行入口是归档根的 main.js。
  assert.ok(dockerfile.includes('scripts/daemon/prepare-release.sh'))
  assert.ok(dockerfile.includes('/distribution/main.js'))
  assert.ok(dockerfile.includes('runtime/code-server/lib/node'))
  assert.ok(dockerfile.includes('FROM debian:bookworm-slim'))
  // 只有一个 Node 二进制：/usr/local/bin/node 指向冻结运行时，npm/npx 跑在同一个 Node 上。
  assert.ok(
    dockerfile.includes('ln -s /opt/kk-studio/daemon/runtime/code-server/lib/node /usr/local/bin/node'),
  )
  assert.ok(!dockerfile.split('---------- runtime ----------')[1].includes('FROM node:'))
})

test('test intent: runtime user creation uses absolute system commands', () => {
  // 运行期 PATH 不含 /usr/sbin；构建时必须仍能调用基础镜像自带的用户管理工具。
  const runtime = text(RELIABILITY_DOCKERFILE).split('---------- runtime ----------')[1]
  assert.ok(runtime.includes('/usr/sbin/groupadd --gid 10001 kkdaemon'))
  assert.ok(runtime.includes('/usr/sbin/useradd --uid 10001'))
})

test('test intent: the entrypoint launches the distribution entry with --config only', async () => {
  const server = await startCardServer({ name: 'docker-reliability', version: '0', daemonConfig: DAEMON_CONFIG })
  try {
    await withTempDir(async (temporary) => {
      const { dist, argvFile, environFile } = writeNodeDist(temporary)
      const root = path.join(temporary, 'daemon-root')
      const secret = 'probe-secret-token'

      const result = await runEntrypoint(baseEnv({
        PROBE_ARGV: argvFile,
        PROBE_ENVIRON: environFile,
        KK_STUDIO_DAEMON_DIST: dist,
        KK_STUDIO_DAEMON_ROOT: root,
        KK_STUDIO_DAEMON_STUDIO_URL: server.url,
        KK_STUDIO_DAEMON_ENVIRONMENT_NAME: 'docker-reliability',
        KK_STUDIO_DAEMON_REGISTRATION_TOKEN: secret,
      }))

      assert.equal(result.status, 0, result.stderr)
      const argv = text(argvFile).split('\n').filter(Boolean)
      assert.ok(argv.includes('--config'))
      const configPath = argv[argv.indexOf('--config') + 1]
      assert.deepEqual(argv.slice(0, 2), [path.join(dist, 'main.js'), '--config'])
      assert.ok(path.isAbsolute(configPath))
      assert.equal(configPath, path.join(root, 'daemon.json'))
      assert.ok(!argv.includes(secret))
      assert.ok(!text(environFile).includes(secret))
      assert.ok(!(result.stderr + result.stdout).includes(secret))
      // 凭证从不进入配置 JSON。
      assert.ok(!text(configPath).includes(secret))
      assert.ok(!JSON.stringify(server.calls).includes(secret))

      const config = JSON.parse(text(configPath))
      assert.deepEqual(Object.keys(config), ['environmentId', 'version', 'daemonConfig'])
      assert.equal(config.environmentId, CARD_ID)
      assert.equal(config.version, '1')
      assert.deepEqual(Object.keys(config.daemonConfig), ['studioUrl', 'bashExecutable', 'lsp', 'proxyUrl'])
      assert.equal(config.daemonConfig.studioUrl, server.url)
      assert.equal(config.daemonConfig.bashExecutable, null)
      assert.equal(config.daemonConfig.lsp, null)
      assert.equal(config.daemonConfig.proxyUrl, null)
      assert.ok(!Object.hasOwn(config, 'note'))
      assert.ok(!Object.hasOwn(config.daemonConfig, 'note'))

      assert.equal(server.card.version, '1')
      assert.deepEqual(server.card.daemonConfig, config.daemonConfig)
      const put = server.calls.find((call) => call.method === 'PUT')
      assert.ok(put, 'entrypoint must correct the card studioUrl')
      assert.equal(put.body.expectedVersion, '0')
      assert.equal(put.body.note, null)
      assert.deepEqual(put.body.daemonConfig, config.daemonConfig)

      const tokenPath = path.join(root, 'daemon.token')
      assert.equal(mode(root), 0o700)
      assert.equal(mode(configPath), 0o600)
      assert.equal(mode(tokenPath), 0o600)
      assert.equal(text(tokenPath).trim(), secret)
    })
  } finally {
    await server.close()
  }
})

test('test intent: the entrypoint does not rewrite an already reachable card', async () => {
  const server = await startCardServer({ id: CARD_ID, name: 'docker-reliability', version: '2', daemonConfig: DAEMON_CONFIG })
  try {
    const reachable = { ...DAEMON_CONFIG, studioUrl: server.url }
    const updated = await fetch(`${server.url}/api/platform/environments/${CARD_ID}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ expectedVersion: '2', note: null, daemonConfig: reachable }),
    })
    assert.equal(updated.status, 200)
    server.calls.length = 0

    await withTempDir(async (temporary) => {
      const { dist } = writeNodeDist(temporary, { realNode: true })
      const root = path.join(temporary, 'daemon-root')
      const result = await runEntrypoint(baseEnv({
        KK_STUDIO_DAEMON_DIST: dist,
        KK_STUDIO_DAEMON_ROOT: root,
        KK_STUDIO_DAEMON_STUDIO_URL: server.url,
        KK_STUDIO_DAEMON_ENVIRONMENT_ID: CARD_ID,
        KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
      }))
      assert.equal(result.status, 0, result.stderr)

      const config = JSON.parse(text(path.join(root, 'daemon.json')))
      assert.equal(config.environmentId, CARD_ID)
      assert.equal(config.version, '3')
      assert.equal(config.daemonConfig.studioUrl, server.url)
      assert.equal(server.calls.length, 1)
      assert.equal(server.calls[0].method, 'GET')
      assert.equal(server.calls[0].path, `/api/platform/environments/${CARD_ID}`)
    })
  } finally {
    await server.close()
  }
})

test('test intent: the entrypoint fails closed without a token', async () => {
  const result = await runEntrypoint(baseEnv())
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_REGISTRATION_TOKEN'))
})

test('test intent: the entrypoint fails closed without a studio URL', async () => {
  const result = await runEntrypoint(baseEnv({
    KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
  }))
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_STUDIO_URL'))
})

test('test intent: the entrypoint fails closed without a card selector', async () => {
  const result = await runEntrypoint(baseEnv({
    KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
    KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
  }))
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_ENVIRONMENT_ID'))
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_ENVIRONMENT_NAME'))
})

test('test intent: the entrypoint rejects a relative root', async () => {
  const result = await runEntrypoint(baseEnv({
    KK_STUDIO_DAEMON_ROOT: 'relative/daemon-root',
    KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
    KK_STUDIO_DAEMON_ENVIRONMENT_NAME: 'docker-reliability',
    KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
  }))
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_ROOT'))
  assert.ok(result.stderr.includes('absolute'))
})

test('test intent: a distribution without entry or frozen runtime is rejected, never replaced by PATH node', async () => {
  for (const missing of ['main.js', 'runtime/code-server/lib/node']) {
    await withTempDir(async (temporary) => {
      const { dist } = writeNodeDist(temporary)
      unlinkSync(path.join(dist, missing))
      const result = await runEntrypoint(baseEnv({
        KK_STUDIO_DAEMON_DIST: dist,
        KK_STUDIO_DAEMON_ROOT: path.join(temporary, 'daemon-root'),
        KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
        KK_STUDIO_DAEMON_ENVIRONMENT_NAME: 'docker-reliability',
        KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
      }))
      assert.notEqual(result.status, 0, missing)
      assert.ok(result.stderr.includes(missing), missing)
    })
  }
})

test('test intent: the config is created owner-only without relying on chmod', async () => {
  const server = await startCardServer({ name: 'docker-reliability', daemonConfig: DAEMON_CONFIG })
  try {
    await withTempDir(async (temporary) => {
      const probeDir = path.join(temporary, 'bin')
      mkdirSync(probeDir)
      // chmod 桩为 no-op：只有写目录的 umask 与写文件的显式 mode 能收敛权限。
      writeFileSync(path.join(probeDir, 'chmod'), '#!/bin/bash\nexit 0\n')
      chmodSync(path.join(probeDir, 'chmod'), 0o755)
      const { dist } = writeNodeDist(temporary, { realNode: true })

      const root = path.join(temporary, 'daemon-root')
      const result = await runEntrypoint(
        baseEnv({
          PATH: `${probeDir}${path.delimiter}${process.env.PATH ?? ''}`,
          KK_STUDIO_DAEMON_DIST: dist,
          KK_STUDIO_DAEMON_ROOT: root,
          KK_STUDIO_DAEMON_STUDIO_URL: server.url,
          KK_STUDIO_DAEMON_ENVIRONMENT_NAME: 'docker-reliability',
          KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
        }),
        { args: ['-c', `umask 000; exec bash '${RELIABILITY_ENTRYPOINT}'`] },
      )

      assert.equal(result.status, 0, result.stderr)
      assert.equal(mode(root), 0o700)
      assert.equal(mode(path.join(root, 'daemon.json')), 0o600)
      assert.equal(mode(path.join(root, 'daemon.token')), 0o600)
    })
  } finally {
    await server.close()
  }
})
