import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'

const root = new URL('../../../../../', import.meta.url)
const docker = readFileSync(new URL('.github/workflows/docker-publish.yml', root), 'utf8')
const release = readFileSync(new URL('.github/workflows/daemon-release.yml', root), 'utf8')

function jobCondition(name) {
  const block = docker.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  \\w+:|$(?![\\s\\S]))`, 'm'))?.[1]
  assert.ok(block, `missing job ${name}`)
  const condition = block.match(/^    if: (.+)$/m)?.[1]
  assert.ok(condition, `missing condition ${name}`)
  return condition === '>-'
    ? block.match(/\$\{\{([\s\S]*?)\}\}/)?.[1].trim()
    : condition
}

function evaluate(expression, branch, event, validateOnly, status = {}) {
  // These workflow gates use the shared JS/Actions boolean subset, with no implicit truthy strings.
  return runInNewContext(expression, {
    github: { ref_name: branch, event_name: event },
    inputs: { validate_only: validateOnly },
    needs: {
      validate: { result: status.repository ?? 'success' },
      validate_windows_daemon: { result: status.windows ?? 'success' },
    },
    always: () => true,
    cancelled: () => status.cancelled ?? false,
  }, { timeout: 100 })
}

test('push gates stay main-only; explicit manual validation runs both gates on any branch', () => {
  // Evaluate the actual expressions, so changing just one job or accepting push inputs fails.
  assert.match(docker, /validate_only:\n\s+description: .+\n\s+type: boolean\n\s+default: false/u)
  for (const job of ['validate', 'validate_windows_daemon']) {
    for (const branch of ['main', 'dev', 'worktree/repair']) {
      for (const event of ['push', 'workflow_dispatch']) {
        for (const optIn of [false, true]) {
          assert.equal(
            evaluate(jobCondition(job), branch, event, optIn),
            branch === 'main' || (event === 'workflow_dispatch' && optIn),
            `${job}: ${branch}/${event}/${optIn}`,
          )
        }
      }
    }
  }
})

test('validation-only never publishes; failed or cancelled gates cannot publish', () => {
  // A success-only happy path is insufficient: preserve the failure/cancellation barrier.
  const expression = jobCondition('publish')
  for (const branch of ['main', 'dev']) {
    for (const repository of ['success', 'failure', 'cancelled', 'skipped']) {
      for (const windows of ['success', 'failure', 'cancelled', 'skipped']) {
        for (const cancelled of [false, true]) {
          const state = { repository, windows, cancelled }
          assert.equal(evaluate(expression, branch, 'workflow_dispatch', true, state), false)
          assert.equal(
            evaluate(expression, branch, 'push', false, state),
            !cancelled && ['success', 'skipped'].includes(repository)
              && ['success', 'skipped'].includes(windows),
          )
        }
      }
    }
  }
  assert.equal(evaluate(expression, 'dev', 'workflow_dispatch', false), true)
})

test('manual validation and image publishing have separate concurrency groups', () => {
  // Validating a branch must not cancel its in-flight image publication.
  const group = docker.match(/^  group: (.+)$/m)?.[1]
  assert.ok(group?.includes('${{ github.ref }}'))
  assert.ok(group?.includes("${{ inputs.validate_only && 'validate' || 'publish' }}"))
})

test('CI provisions and selects the PostgreSQL fixture client major before validation', () => {
  // Installing a client alone is insufficient: PATH must select every binary from that major.
  const fixture = readFileSync(new URL('scripts/ops/tests/test_agent_catalog_integration.py', root), 'utf8')
  const major = fixture.match(/^POSTGRES_MAJOR = (\d+)$/m)?.[1]
  assert.ok(major)
  assert.match(fixture, /f"postgres:\{POSTGRES_MAJOR\}-alpine"/u)
  assert.ok(docker.includes(`POSTGRES_VERSION: '${major}'`))
  const setup = docker.indexOf('- name: Set up PostgreSQL client tools')
  const selection = docker.indexOf('- name: Verify PostgreSQL client selection')
  const scripts = docker.indexOf('- name: Run repository script tests')
  const validation = docker.indexOf('- name: Verify Java sources, tests and coverage')
  assert.ok(setup > 0 && setup < selection && selection < scripts && scripts < validation)
  const block = docker.slice(setup, validation)
  assert.ok(block.includes('sudo apt-get install --yes --no-install-recommends "postgresql-client-${POSTGRES_VERSION}"'))
  assert.ok(block.includes('echo "/usr/lib/postgresql/${POSTGRES_VERSION}/bin" >> "${GITHUB_PATH}"'))
  assert.ok(block.includes('for tool in psql pg_dump pg_restore createdb pg_isready; do'))
  assert.ok(block.includes('test "$(command -v "${tool}")" = "/usr/lib/postgresql/${POSTGRES_VERSION}/bin/${tool}"'))
})

test('both release workflows run the second Windows host even after the first fails', () => {
  // Do not use continue-on-error: either native host must still fail the release gate.
  for (const [name, source] of [['docker', docker], ['release', release]]) {
    const block = source.match(/- name: Test installer with PowerShell 7\n([\s\S]*?)(?=\n\s+- name:|\n  \w+:)/)?.[1]
    assert.ok(block, `${name}: missing PS7 step`)
    assert.match(block, /if: \$\{\{ !cancelled\(\) \}\}/u)
    assert.match(block, /shell: pwsh/u)
    assert.match(block, /run: .\\scripts\\daemon\\tests\\test_daemon_install_windows.ps1/u)
    assert.doesNotMatch(source, /continue-on-error:/u)
  }
})
