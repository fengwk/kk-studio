import assert from 'node:assert/strict'
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(TEST_DIR, '../..')
const SCRIPT = path.join(REPO_ROOT, 'scripts/performance.sh')

const LOOPBACK_CASES = [
  {
    name: 'HTTP IPv4 loopback',
    proxy: 'http://127.0.0.1:45678',
    variable: 'CANVAS_TEST_BUILD_HTTP_PROXY',
  },
  {
    name: 'HTTPS localhost',
    proxy: 'https://localhost:45679',
    variable: 'CANVAS_TEST_BUILD_HTTPS_PROXY',
  },
  {
    name: 'HTTP IPv6 loopback',
    proxy: 'http://[::1]:45680',
    variable: 'CANVAS_TEST_BUILD_HTTP_PROXY',
  },
]

for (const { name, proxy, variable } of LOOPBACK_CASES) {
  test(`loopback ${name} selects host build network`, () => {
    const run = runEntrypoint({ [variable]: proxy })
    assert.equal(run.result.status, 0, run.result.stderr)
    assert.equal(readBuildNetwork(run.dockerLog), 'host')
    assert.match(run.dockerLog, /compose .*down --volumes --remove-orphans/)
    assert.equal(`${run.result.stdout}\n${run.result.stderr}`.includes(proxy), false)
  })
}

test('an explicit build network overrides loopback proxy detection', () => {
  const proxy = 'http://127.0.0.1:45681'
  const run = runEntrypoint({
    CANVAS_TEST_BUILD_HTTP_PROXY: proxy,
    CANVAS_TEST_BUILD_NETWORK: 'default',
  })

  assert.equal(run.result.status, 0, run.result.stderr)
  assert.equal(readBuildNetwork(run.dockerLog), 'default')
  assert.doesNotMatch(`${run.result.stdout}\n${run.result.stderr}`, /45681/)
})

test('a non-loopback proxy keeps the default build network', () => {
  const proxy = 'http://proxy.example.test:45682'
  const run = runEntrypoint({ CANVAS_TEST_BUILD_HTTP_PROXY: proxy })

  assert.equal(run.result.status, 0, run.result.stderr)
  assert.equal(readBuildNetwork(run.dockerLog), 'default')
  assert.doesNotMatch(`${run.result.stdout}\n${run.result.stderr}`, /45682/)
})

function runEntrypoint(extraEnvironment) {
  const root = mkdtempSync(path.join(tmpdir(), 'kk-studio-performance-entrypoint-'))
  const bin = path.join(root, 'bin')
  const dockerLog = path.join(root, 'docker.log')
  const reportRoot = path.join(root, 'report')
  mkdirSync(bin, { recursive: true })
  writeFakeTools(bin, dockerLog)

  const environment = {
    ...process.env,
    PATH: `${bin}${path.delimiter}${process.env.PATH ?? ''}`,
    FAKE_DOCKER_LOG: dockerLog,
    CANVAS_TEST_APP_IMAGE: 'kk-studio-app:performance-entrypoint-test',
    ...extraEnvironment,
  }
  for (const variable of [
    'HTTP_PROXY',
    'HTTPS_PROXY',
    'http_proxy',
    'https_proxy',
    'NO_PROXY',
    'no_proxy',
    'CANVAS_TEST_BUILD_HTTP_PROXY',
    'CANVAS_TEST_BUILD_HTTPS_PROXY',
    'CANVAS_TEST_BUILD_NO_PROXY',
  ]) {
    if (!(variable in extraEnvironment)) {
      delete environment[variable]
    }
  }
  if (!('CANVAS_TEST_BUILD_NETWORK' in extraEnvironment)) {
    delete environment.CANVAS_TEST_BUILD_NETWORK
  }

  try {
    const result = spawnSync(
      'bash',
      [SCRIPT, '--duration-seconds', '1', '--report-root', reportRoot],
      {
        cwd: REPO_ROOT,
        encoding: 'utf8',
        env: environment,
      },
    )
    return {
      result,
      dockerLog: readFileSync(dockerLog, 'utf8'),
    }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

function writeFakeTools(bin, dockerLog) {
  writeFileSync(
    path.join(bin, 'node'),
    '#!/usr/bin/env bash\nexit 0\n',
    { mode: 0o755 },
  )
  const docker = path.join(bin, 'docker')
  writeFileSync(
    docker,
    `#!/usr/bin/env bash
set -eu
if [[ "\${1:-}" == "compose" ]]; then
  printf 'compose %s\\n' "$*" >>"\${FAKE_DOCKER_LOG}"
elif [[ "\${1:-}" == "build" ]]; then
  network=
  while (($#)); do
    if [[ "$1" == "--network" ]]; then
      shift
      network="\${1-}"
    fi
    shift
  done
  printf 'build-network=%s\\n' "$network" >>"\${FAKE_DOCKER_LOG}"
fi
`,
    { mode: 0o755 },
  )
  writeFileSync(dockerLog, '')
  chmodSync(dockerLog, 0o600)
}

function readBuildNetwork(dockerLog) {
  const match = dockerLog.match(/^build-network=(.*)$/m)
  assert.ok(match, `fake docker did not record a build: ${dockerLog}`)
  return match[1]
}
