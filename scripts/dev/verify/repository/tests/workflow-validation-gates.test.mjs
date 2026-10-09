import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'

const root = new URL('../../../../../', import.meta.url)
const docker = readFileSync(new URL('.github/workflows/docker-publish.yml', root), 'utf8')
const release = readFileSync(new URL('.github/workflows/daemon-release.yml', root), 'utf8')

function jobBlock(name) {
  const block = docker.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  \\w+:|$(?![\\s\\S]))`, 'm'))?.[1]
  assert.ok(block, `missing job ${name}`)
  return block
}

function jobCondition(name) {
  const block = jobBlock(name)
  const condition = block.match(/^    if: (.+)$/m)?.[1]
  assert.ok(condition, `missing condition ${name}`)
  return condition === '>-'
    ? block.match(/\$\{\{([\s\S]*?)\}\}/)?.[1].trim()
    : condition
}

function validationSteps() {
  // Parse actual step boundaries, not command mentions in comments or other jobs.
  return [...jobBlock('validate').matchAll(/^      - ([\s\S]*?)(?=^      - |$(?![\s\S]))/gm)]
    .map((match) => match[1])
}

function stepField(step, field, indent = 8) {
  const value = step.match(new RegExp(`^ {${indent}}${field}: (.+)$`, 'm'))?.[1]
  if (value !== '|') {
    return value
  }
  const lines = step.match(new RegExp(`^ {${indent}}${field}: \\|\\n((?: {${indent + 2}}.+\\n)+)`, 'm'))?.[1]
  assert.ok(lines, `missing block value for ${field}`)
  return lines.trim().split('\n').map((line) => line.trim()).join('\n')
}

function evaluate(expression, branch, event, validateOnly, status = {}) {
  // These workflow gates use the shared JS/Actions boolean subset, with no implicit truthy strings.
  return runInNewContext(expression, {
    github: { ref_name: branch, event_name: event },
    inputs: { validate_only: validateOnly },
    needs: {
      validate: { result: status.repository ?? 'success' },
      validate_windows_daemon: { result: status.windows ?? 'success' },
      validate_unix_daemon: { result: status.unix ?? 'success' },
    },
    always: () => true,
    cancelled: () => status.cancelled ?? false,
  }, { timeout: 100 })
}

test('push gates stay main-only; explicit manual validation runs all gates on any branch', () => {
  // Evaluate the actual expressions, so changing just one job or accepting push inputs fails.
  assert.match(docker, /validate_only:\n\s+description: .+\n\s+type: boolean\n\s+default: false/u)
  for (const job of ['validate', 'validate_windows_daemon', 'validate_unix_daemon']) {
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
        for (const unix of ['success', 'failure', 'cancelled', 'skipped']) {
          for (const cancelled of [false, true]) {
            const state = { repository, windows, unix, cancelled }
            assert.equal(evaluate(expression, branch, 'workflow_dispatch', true, state), false)
            assert.equal(
              evaluate(expression, branch, 'push', false, state),
              !cancelled && ['success', 'skipped'].includes(repository)
                && ['success', 'skipped'].includes(windows)
                && ['success', 'skipped'].includes(unix),
            )
          }
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

test('frontend coverage and offline layout are required gates using the locked browser', () => {
  // Coverage replaces the unit-test run; every preparation/gate must fail the job on error.
  const steps = validationSteps()
  const commands = [
    'npm --prefix frontend ci',
    'npm --prefix frontend run lint',
    'npm --prefix frontend run coverage',
    'npm --prefix frontend run build',
    'npx --no-install playwright install --with-deps chromium',
    'npm --prefix frontend run test:layout',
  ]
  let previous = -1
  for (const command of commands) {
    const matching = steps.filter((step) => stepField(step, 'run') === command)
    assert.equal(matching.length, 1, `expected exactly one required step: ${command}`)
    const step = matching[0]
    const index = steps.indexOf(step)
    assert.ok(index > previous, `incorrect frontend gate order: ${command}`)
    previous = index
    assert.equal(stepField(step, 'if'), undefined, `${command} must not be conditional`)
    assert.doesNotMatch(step, /continue-on-error:|(?:\|\||&&)\s*true|--passWithNoTests/u)
  }
  assert.ok(!steps.some((step) => stepField(step, 'run') === 'npm --prefix frontend run test'))
  const browser = steps.find((step) => stepField(step, 'run') === commands[4])
  assert.equal(stepField(browser, 'working-directory'), 'frontend')
  const lock = JSON.parse(readFileSync(new URL('frontend/package-lock.json', root), 'utf8'))
  const playwright = lock.packages['node_modules/playwright']
  assert.ok(playwright?.version)
  assert.equal(playwright.bin.playwright, 'cli.js')
  assert.equal(lock.packages['node_modules/@playwright/test'].dependencies.playwright, playwright.version)
  assert.doesNotMatch(jobBlock('validate'), /continue-on-error:/u)
})

test('frontend diagnostics survive failed gates and upload only report directories', () => {
  // Exact report paths prevent accidental source/secret uploads; always preserves failure evidence.
  const steps = validationSteps()
  const uploads = steps.filter((step) => stepField(step, 'uses')?.startsWith('actions/upload-artifact@'))
  assert.equal(uploads.length, 1)
  const upload = uploads[0]
  assert.equal(stepField(upload, 'uses'), 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4')
  assert.equal(stepField(upload, 'if'), '${{ always() }}')
  assert.equal(stepField(upload, 'name', 10), 'frontend-diagnostics')
  assert.equal(stepField(upload, 'path', 10), 'frontend/coverage/\nreports/layout/')
  assert.equal(stepField(upload, 'retention-days', 10), '7')
  assert.equal(stepField(upload, 'if-no-files-found', 10), 'ignore')
  const layout = steps.findIndex((step) => stepField(step, 'run') === 'npm --prefix frontend run test:layout')
  assert.ok(layout >= 0 && steps.indexOf(upload) > layout)
  assert.match(jobBlock('publish'), /needs:\n      - validate\n/u)
})

test('CI provisions and selects the PostgreSQL fixture client major before validation', () => {
  // Installing a client alone is insufficient: PATH must select every binary from that major.
  const fixture = readFileSync(new URL('scripts/ops/tests/test_reset_database_integration.py', root), 'utf8')
  const major = fixture.match(/^POSTGRES_MAJOR = (\d+)$/m)?.[1]
  assert.ok(major)
  assert.match(fixture, /f"postgres:\{POSTGRES_MAJOR\}-alpine"/u)
  for (const [source, validationName] of [
    [docker, 'Verify Java sources, tests and coverage'],
    [release, 'Verify the daemon module'],
  ]) {
    assert.ok(source.includes(`POSTGRES_VERSION: '${major}'`))
    const setup = source.indexOf('- name: Set up PostgreSQL client tools')
    const selection = source.indexOf('- name: Verify PostgreSQL client selection')
    const scripts = source.indexOf('- name: Run repository script tests')
    const validation = source.indexOf(`- name: ${validationName}`)
    assert.ok(setup > 0 && setup < selection && selection < scripts && scripts < validation)
    const block = source.slice(setup, validation)
    const commands = block.replaceAll(/\\\r?\n[ \t]*/gu, ' ')
    const aptPrefix = String.raw`^[ \t]*sudo[ \t]+(?:env[ \t]+DEBIAN_FRONTEND=noninteractive[ \t]+)?apt-get(?:[ \t]+-o[ \t]+[^ \t\n]+)*[ \t]+`
    const update = commands.match(new RegExp(`${aptPrefix}update[ \\t]*$`, 'mu'))
    assert.ok(block.includes('https://www.postgresql.org/media/keys/ACCC4CF8.asc'))
    assert.ok(block.includes('URIs: https://apt.postgresql.org/pub/repos/apt'))
    assert.ok(block.includes('Suites: ${VERSION_CODENAME}-pgdg'))
    assert.ok(block.includes('Signed-By: /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc'))
    assert.ok(update, 'the client setup must update the configured package indexes')
    assert.ok(commands.indexOf('/etc/apt/sources.list.d/pgdg.sources') < update.index)
    assert.doesNotMatch(block, /allow-unauthenticated|trusted=yes/u)
    assert.match(commands, new RegExp(`${aptPrefix}install --yes --no-install-recommends "postgresql-client-\\$\\{POSTGRES_VERSION\\}"[ \\t]*$`, 'mu'))
    assert.ok(block.includes('echo "/usr/lib/postgresql/${POSTGRES_VERSION}/bin" >> "${GITHUB_PATH}"'))
    assert.ok(block.includes('for tool in psql pg_dump pg_restore createdb pg_isready; do'))
    assert.ok(block.includes('test "$(command -v "${tool}")" = "/usr/lib/postgresql/${POSTGRES_VERSION}/bin/${tool}"'))
  }
})

test('repository PostgreSQL bootstrap bounds network and package-lock waits', () => {
  const setup = validationSteps().find((step) => stepField(step, 'name', 0) === 'Set up PostgreSQL client tools')
  assert.ok(setup)
  assert.equal(stepField(setup, 'timeout-minutes'), '10')
  const commands = stepField(setup, 'run').replaceAll(/\\\r?\n[ \t]*/gu, ' ')
  assert.match(commands, /curl[^\n]*--connect-timeout 10[^\n]*--max-time 60/u)
  assert.match(commands, /apt-get[^\n]*Acquire::http::Timeout=30[^\n]*Acquire::https::Timeout=30[^\n]* update/u)
  assert.match(commands, /env DEBIAN_FRONTEND=noninteractive apt-get[^\n]*Acquire::http::Timeout=30[^\n]*Acquire::https::Timeout=30[^\n]*DPkg::Lock::Timeout=60[^\n]* install/u)
  assert.doesNotMatch(commands, /continue-on-error|allow-unauthenticated|trusted=yes/u)
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
