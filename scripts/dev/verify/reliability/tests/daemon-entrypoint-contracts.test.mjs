/** Permanent guards for the reliability stack's Node daemon bootstrap and token boundary. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const RELIABILITY_COMPOSE = path.join(REPOSITORY_ROOT, 'deploy/reliability/compose.yaml')
const RELIABILITY_ENTRYPOINT = path.join(REPOSITORY_ROOT, 'deploy/reliability/daemon-entrypoint.sh')
const RELIABILITY_DOCKERFILE = path.join(REPOSITORY_ROOT, 'deploy/reliability/daemon.Dockerfile')

const mode = (file) => statSync(file).mode & 0o777
const text = (file) => readFileSync(file, 'utf8')
const count = (haystack, needle) => haystack.split(needle).length - 1

function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-entrypoint-'))
  try {
    return body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/**
 * 按发行布局写一个最小 Daemon 目录：根目录入口 + 冻结运行时。
 *
 * 默认运行时是一个可执行包装脚本：`-e` 交给真实 Node（入口用它写 JSON），其它调用被记录成
 * daemon 启动参数，因此断言的是入口真实的 exec 行为而不是静态文本。
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
          `if [ "$1" = "-e" ]; then exec "${process.execPath}" "$@"; fi\n` +
          'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n' +
          'env >"$PROBE_ENVIRON"\n',
  )
  chmodSync(runtime, 0o755)
  return { dist, argvFile, environFile }
}

function runEntrypoint(env, { script = RELIABILITY_ENTRYPOINT, args = [script] } = {}) {
  return spawnSync('bash', args, { env, encoding: 'utf8' })
}

const baseEnv = () => {
  const environment = { ...process.env }
  for (const name of Object.keys(environment)) {
    if (name.startsWith('KK_STUDIO_DAEMON_')) {
      delete environment[name]
    }
  }
  return environment
}

test('test intent: compose injects only environment; the entrypoint materializes config and token', () => {
  const compose = text(RELIABILITY_COMPOSE)

  assert.ok(compose.includes('KK_STUDIO_DAEMON_REGISTRATION_TOKEN'))
  assert.ok(compose.includes('KK_STUDIO_DAEMON_STUDIO_URL: http://app:8080'))
  assert.ok(compose.includes('e2e-token-reliability'))
  // 旧 CLI/启动参数必须保持删除状态；配置只经共享 daemon.json 文件。
  for (const removed of ['--gateway-uri', '--data-dir', '--note', '--registration-token', '--environment-root']) {
    assert.ok(!compose.includes(removed), removed)
  }
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
  assert.ok(!dockerfile.includes('ENTRYPOINT ["java"'), '直接 exec java 会绕过配置/凭证物化')
  for (const removedJvm of ['eclipse-temurin', 'daemon.jar', 'maven:']) {
    assert.ok(!dockerfile.includes(removedJvm), removedJvm)
  }
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

test('test intent: the entrypoint launches the distribution entry with --config only', () => {
  // 入口必须物化共享 daemon.json/owner-only token，并用冻结 Node 运行发行根入口。
  withTempDir((temporary) => {
    const { dist, argvFile, environFile } = writeNodeDist(temporary)
    const root = path.join(temporary, 'daemon-root')
    const secret = 'probe-secret-token'
    const studioUrl = 'https://studio.example.com'
    const note = 'Probe note — 日本語'

    const result = runEntrypoint({
      ...baseEnv(),
      PROBE_ARGV: argvFile,
      PROBE_ENVIRON: environFile,
      KK_STUDIO_DAEMON_DIST: dist,
      KK_STUDIO_DAEMON_ROOT: root,
      KK_STUDIO_DAEMON_STUDIO_URL: studioUrl,
      KK_STUDIO_DAEMON_NOTE: note,
      KK_STUDIO_DAEMON_REGISTRATION_TOKEN: secret,
    })

    assert.equal(result.status, 0, result.stderr)
    const argv = text(argvFile).split('\n').filter(Boolean)
    assert.ok(argv.includes('--config'))
    const configPath = argv[argv.indexOf('--config') + 1]
    assert.deepEqual(argv.slice(0, 2), [path.join(dist, 'main.js'), '--config'])
    assert.ok(path.isAbsolute(configPath))
    assert.equal(configPath, path.join(root, 'daemon.json'))
    // 旧 CLI 双源配置与 JVM 启动形式必须不再存在。
    for (const removed of ['--gateway-uri', '--data-dir', '--registration-token-file', '-jar', 'daemon.jar']) {
      assert.ok(!argv.includes(removed), removed)
    }
    assert.ok(!argv.includes(secret))
    assert.ok(!text(environFile).includes(secret))
    assert.ok(!(result.stderr + result.stdout).includes(secret))
    // 凭证从不进入配置 JSON。
    assert.ok(!text(configPath).includes(secret))

    const config = JSON.parse(text(configPath))
    assert.deepEqual(new Set(Object.keys(config)), new Set(['studioUrl', 'note']))
    assert.equal(config.studioUrl, studioUrl)
    assert.equal(config.note, note)

    const tokenPath = path.join(root, 'daemon.token')
    assert.equal(mode(root), 0o700)
    assert.equal(mode(configPath), 0o600)
    assert.equal(mode(tokenPath), 0o600)
    assert.equal(text(tokenPath).trim(), secret)
  })
})

test('test intent: the config omits note when it is unset', () => {
  withTempDir((temporary) => {
    const { dist } = writeNodeDist(temporary, { realNode: true })
    const root = path.join(temporary, 'daemon-root')
    const result = runEntrypoint({
      ...baseEnv(),
      KK_STUDIO_DAEMON_DIST: dist,
      KK_STUDIO_DAEMON_ROOT: root,
      KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
      KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
    })
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(new Set(Object.keys(JSON.parse(text(path.join(root, 'daemon.json'))))), new Set(['studioUrl']))
  })
})

test('test intent: the entrypoint fails closed without a token', () => {
  const result = runEntrypoint(baseEnv())
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_REGISTRATION_TOKEN'))
})

test('test intent: the entrypoint fails closed without a studio URL', () => {
  const result = runEntrypoint({ ...baseEnv(), KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token' })
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_STUDIO_URL'))
})

test('test intent: the entrypoint rejects a relative root', () => {
  const result = runEntrypoint({
    ...baseEnv(),
    KK_STUDIO_DAEMON_ROOT: 'relative/daemon-root',
    KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
    KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
  })
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('KK_STUDIO_DAEMON_ROOT'))
  assert.ok(result.stderr.includes('absolute'))
})

test('test intent: a distribution without entry or frozen runtime is rejected, never replaced by PATH node', () => {
  for (const missing of ['main.js', 'runtime/code-server/lib/node']) {
    withTempDir((temporary) => {
      const { dist } = writeNodeDist(temporary)
      unlinkSync(path.join(dist, missing))
      const result = runEntrypoint({
        ...baseEnv(),
        KK_STUDIO_DAEMON_DIST: dist,
        KK_STUDIO_DAEMON_ROOT: path.join(temporary, 'daemon-root'),
        KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
        KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
      })
      assert.notEqual(result.status, 0, missing)
      assert.ok(result.stderr.includes(missing), missing)
    })
  }
})

test('test intent: the config is created owner-only without relying on chmod', () => {
  // 配置文件必须写入时即 owner-only：桩掉 chmod 并把 umask 全开也不能出现 world-readable 窗口。
  withTempDir((temporary) => {
    const probeDir = path.join(temporary, 'bin')
    mkdirSync(probeDir)
    // chmod 桩为 no-op：只有 writeFileSync 的显式 mode 与 umask 能收敛权限。
    writeFileSync(path.join(probeDir, 'chmod'), '#!/bin/bash\nexit 0\n')
    chmodSync(path.join(probeDir, 'chmod'), 0o755)
    const { dist } = writeNodeDist(temporary, { realNode: true })

    const root = path.join(temporary, 'daemon-root')
    const result = runEntrypoint(
      {
        ...baseEnv(),
        PATH: `${probeDir}${path.delimiter}${process.env.PATH ?? ''}`,
        KK_STUDIO_DAEMON_DIST: dist,
        KK_STUDIO_DAEMON_ROOT: root,
        KK_STUDIO_DAEMON_STUDIO_URL: 'http://app:8080',
        KK_STUDIO_DAEMON_REGISTRATION_TOKEN: 'probe-secret-token',
      },
      { args: ['-c', `umask 000; exec bash '${RELIABILITY_ENTRYPOINT}'`] },
    )

    assert.equal(result.status, 0, result.stderr)
    assert.equal(mode(path.join(root, 'daemon.json')), 0o600)
    assert.equal(mode(path.join(root, 'daemon.token')), 0o600)
  })
})
