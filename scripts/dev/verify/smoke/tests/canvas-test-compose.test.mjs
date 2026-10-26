/** deploy/test/compose.yaml 的静态配置契约验证（不拉起全栈、不执行镜像构建）。 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const COMPOSE_FILE = path.join(REPOSITORY_ROOT, 'deploy/test/compose.yaml')
const dockerAvailable = spawnSync('docker', ['compose', 'version'], { stdio: 'ignore' }).status === 0

function composeConfig(...extraArgs) {
  const result = spawnSync('docker', ['compose', '-f', COMPOSE_FILE, ...extraArgs, 'config', '--format', 'json'], {
    cwd: REPOSITORY_ROOT,
    encoding: 'utf8',
  })
  assert.equal(result.status, 0, result.stderr)
  return JSON.parse(result.stdout)
}

test('test intent: the default profile omits app so the resident stack stays minimal', { skip: !dockerAvailable && 'docker compose is not available' }, () => {
  const { services = {} } = composeConfig()
  for (const name of ['postgres', 'minio', 'minio-init', 'http-mock']) {
    assert.ok(name in services, name)
  }
  assert.ok(!('app' in services))
})

test('test intent: the app profile waits only for the data plane and the HTTP mock', { skip: !dockerAvailable && 'docker compose is not available' }, () => {
  const config = composeConfig('--profile', 'app')
  const app = config.services.app
  assert.deepEqual(new Set(Object.keys(app.depends_on ?? {})), new Set(['postgres', 'minio', 'minio-init', 'http-mock']))
  // Only the per-instance exit journal is persistent; source/build directories stay out of the app.
  assert.deepEqual(app.volumes.map(({ type, source, target }) => ({ type, source, target })), [
    { type: 'volume', source: 'execution-exits', target: '/app/execution-exits' },
  ])
  assert.ok(Object.hasOwn(config.volumes, 'execution-exits'))
  assert.equal(app.environment.KK_STUDIO_EXIT_CONFIRMATION_DIRECTORY, '/app/execution-exits')
})
