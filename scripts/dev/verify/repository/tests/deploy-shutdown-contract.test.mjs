/** 4.0.0 自带部署模板的停机预算与退出确认卷独占契约。 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const dockerAvailable = spawnSync('docker', ['compose', 'version'], { stdio: 'ignore' }).status === 0

// 每个 App 实例都必须获得独立的 journal 卷；distributed 保留既有的短 drain 预算。
// reliability 按实际 stack 脚本使用独立 project name，卷独占按 Compose 解析后的实际卷名判断。
const TEMPLATES = [
  { file: 'deploy/local/compose.yaml', profiles: [], apps: ['app'], drain: '60s', lease: '30s' },
  { file: 'deploy/test/compose.yaml', profiles: ['app'], apps: ['app'], drain: '60s', lease: '30s' },
  {
    file: 'deploy/test/compose.yaml',
    projectName: 'kk-studio-reliability',
    profiles: ['reliability'],
    apps: ['reliability-app'],
    drain: '60s',
    lease: '30s',
  },
  { file: 'deploy/distributed/compose.yaml', profiles: [], apps: ['app-a', 'app-b'], drain: '10s', lease: '10s' },
]

// 调用者的 Compose 选择与模板默认值不能被带进静态断言。
const ISOLATED_ENVIRONMENT_KEYS = [
  'COMPOSE_FILE',
  'COMPOSE_PROFILES',
  'COMPOSE_PROJECT_NAME',
  'KK_STUDIO_HARNESS_RUNTIME_DRAIN_TIMEOUT',
  'KK_STUDIO_HARNESS_DISPATCHER_LEASE_DURATION',
]

const configCache = new Map()

function composeConfig(template) {
  const cacheKey = `${template.file}:${template.projectName ?? ''}:${template.profiles.join(',')}`
  if (!configCache.has(cacheKey)) {
    const args = ['compose', '-f', path.join(REPOSITORY_ROOT, template.file)]
    if (template.projectName) {
      args.push('--project-name', template.projectName)
    }
    for (const profile of template.profiles) {
      args.push('--profile', profile)
    }
    args.push('config', '--format', 'json')
    const environment = { ...process.env }
    for (const key of ISOLATED_ENVIRONMENT_KEYS) {
      delete environment[key]
    }
    const result = spawnSync('docker', args, {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
      env: environment,
    })
    assert.equal(result.status, 0, `${template.file}: ${result.stderr}`)
    configCache.set(cacheKey, JSON.parse(result.stdout))
  }
  return configCache.get(cacheKey)
}

/** Compose 会把 duration 规范化成 2m0s 一类形式；契约只关心实际秒数。 */
function durationSeconds(value) {
  const match = typeof value === 'string' && value.length > 0
    ? /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(value)
    : null
  assert.ok(match, `unsupported duration ${JSON.stringify(value)}`)
  return Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0)
}

test(
  'test intent: every Studio app template reserves 120s stop grace, 100s Spring shutdown, and its own journal volume',
  { skip: !dockerAvailable && 'docker compose is not available' },
  () => {
    const journalOwners = new Map()
    for (const template of TEMPLATES) {
      const config = composeConfig(template)
      for (const app of template.apps) {
        const service = config.services[app]
        assert.ok(service, `${template.file}: missing ${app}`)
        const label = `${template.file}:${app}`
        const environment = service.environment ?? {}
        const stopSeconds = durationSeconds(service.stop_grace_period)
        const springSeconds = durationSeconds(environment.SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE)
        const drainSeconds = durationSeconds(environment.KK_STUDIO_HARNESS_RUNTIME_DRAIN_TIMEOUT)
        const leaseSeconds = durationSeconds(environment.KK_STUDIO_HARNESS_DISPATCHER_LEASE_DURATION)

        assert.equal(stopSeconds, 120, `${label} stop_grace_period`)
        assert.equal(springSeconds, 100, `${label} Spring shutdown phase`)
        assert.equal(drainSeconds, durationSeconds(template.drain), `${label} drain timeout`)
        assert.equal(leaseSeconds, durationSeconds(template.lease), `${label} dispatcher lease`)
        assert.ok(stopSeconds > springSeconds, `${label} stop grace must cover Spring shutdown`)
        assert.ok(stopSeconds > drainSeconds + leaseSeconds, `${label} stop grace must cover drain + lease`)

        assert.equal(environment.KK_STUDIO_EXIT_CONFIRMATION_DIRECTORY, '/app/execution-exits', label)
        const journalMounts = (service.volumes ?? []).filter((volume) => volume.target === '/app/execution-exits')
        assert.equal(journalMounts.length, 1, `${label} must mount exactly one execution-exit journal`)
        assert.equal(journalMounts[0].type, 'volume', `${label} journal must be a persistent volume`)
        const journalVolume = config.volumes?.[journalMounts[0].source]
        assert.ok(journalVolume?.name, `${label} journal volume declaration`)

        const owner = journalOwners.get(journalVolume.name)
        assert.equal(owner, undefined, `${label} shares journal volume ${journalVolume.name} with ${owner}`)
        journalOwners.set(journalVolume.name, label)
      }
    }
  },
)
