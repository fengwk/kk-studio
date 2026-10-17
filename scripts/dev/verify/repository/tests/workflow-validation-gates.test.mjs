import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'

const root = new URL('../../../../../', import.meta.url)
const docker = readFileSync(new URL('.github/workflows/docker-publish.yml', root), 'utf8')
const release = readFileSync(new URL('.github/workflows/daemon-release.yml', root), 'utf8')
const daemonCi = readFileSync(new URL('.github/workflows/daemon-ci.yml', root), 'utf8')

function jobBlock(source, name) {
  const block = source.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  \\w+:|$(?![\\s\\S]))`, 'm'))?.[1]
  assert.ok(block, `missing job ${name}`)
  return block
}

function jobCondition(source, name) {
  const block = jobBlock(source, name)
  const condition = block.match(/^    if: (.+)$/m)?.[1]
  assert.ok(condition, `missing condition ${name}`)
  return condition === '>-'
    ? block.match(/\$\{\{([\s\S]*?)\}\}/)?.[1].trim()
    : condition
}

function validationSteps() {
  // Parse actual step boundaries, not command mentions in comments or other jobs.
  return [...jobBlock(docker, 'validate').matchAll(/^      - ([\s\S]*?)(?=^      - |$(?![\s\S]))/gm)]
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

const PUBLISH_GATES = ['validate', 'daemon_ci', 'validate_unix_daemon', 'validate_windows_daemon']

function evaluate(expression, branch, event, validateOnly, status = {}) {
  // These workflow gates use the shared JS/Actions boolean subset, with no implicit truthy strings.
  return runInNewContext(expression, {
    github: { ref_name: branch, event_name: event },
    inputs: { validate_only: validateOnly },
    needs: Object.fromEntries(PUBLISH_GATES.map((gate) => [gate, { result: status[gate] ?? 'success' }])),
    always: () => true,
    cancelled: () => status.cancelled ?? false,
  }, { timeout: 100 })
}

test('push gates stay main-only; explicit manual validation runs all gates on any branch', () => {
  // Evaluate the actual expressions, so changing just one job or accepting push inputs fails.
  assert.match(docker, /validate_only:\n\s+description: .+\n\s+type: boolean\n\s+default: false/u)
  for (const job of PUBLISH_GATES) {
    for (const branch of ['main', 'dev', 'worktree/repair']) {
      for (const event of ['push', 'workflow_dispatch']) {
        for (const optIn of [false, true]) {
          assert.equal(
            evaluate(jobCondition(docker, job), branch, event, optIn),
            branch === 'main' || (event === 'workflow_dispatch' && optIn),
            `${job}: ${branch}/${event}/${optIn}`,
          )
        }
      }
    }
  }
})

test('validation-only never publishes; failed or cancelled gates cannot publish', () => {
  // A success-only happy path is insufficient: preserve the failure/cancellation barrier, and keep
  // the group uniform — main needs four successes, other refs need four skips, mixed never publishes.
  const expression = jobCondition(docker, 'publish')
  assert.match(jobBlock(docker, 'publish'), new RegExp(`needs:\\n${PUBLISH_GATES.map((gate) => `      - ${gate}\\n`).join('')}`, 'u'))
  const publishes = (branch, cancelled, results) => {
    if (cancelled) {
      return false
    }
    const uniform = (value) => PUBLISH_GATES.every((gate) => results[gate] === value)
    return branch === 'main' ? uniform('success') : uniform('skipped')
  }
  for (const branch of ['main', 'dev', 'worktree/repair']) {
    for (const gate of PUBLISH_GATES) {
      for (const result of ['success', 'failure', 'cancelled', 'skipped']) {
        for (const cancelled of [false, true]) {
          const results = Object.fromEntries(PUBLISH_GATES.map((name) => [name, name === gate ? result : 'success']))
          assert.equal(
            evaluate(expression, branch, 'workflow_dispatch', true, { ...results, cancelled }),
            false,
            `${branch}/${gate}=${result}/cancelled=${cancelled}`,
          )
          assert.equal(
            evaluate(expression, branch, 'push', false, { ...results, cancelled }),
            publishes(branch, cancelled, results),
            `${branch}/${gate}=${result}/cancelled=${cancelled}`,
          )
        }
      }
    }
  }
  const allSkipped = Object.fromEntries(PUBLISH_GATES.map((gate) => [gate, 'skipped']))
  assert.equal(publishes('main', false, allSkipped), false)
  assert.equal(evaluate(expression, 'main', 'push', false, {}), true)
  assert.equal(evaluate(expression, 'dev', 'push', false, {}), false)
  assert.equal(evaluate(expression, 'dev', 'push', false, allSkipped), true)
  assert.equal(evaluate(expression, 'worktree/repair', 'workflow_dispatch', false, allSkipped), true)
  assert.equal(evaluate(expression, 'dev', 'workflow_dispatch', true, allSkipped), false)
  assert.equal(evaluate(expression, 'main', 'push', false, { cancelled: true }), false)
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
    'npm --prefix frontend run typecheck:test',
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
  const browser = steps.find((step) => stepField(step, 'run') === 'npx --no-install playwright install --with-deps chromium')
  assert.equal(stepField(browser, 'working-directory'), 'frontend')
  const lock = JSON.parse(readFileSync(new URL('frontend/package-lock.json', root), 'utf8'))
  const playwright = lock.packages['node_modules/playwright']
  assert.ok(playwright?.version)
  assert.equal(playwright.bin.playwright, 'cli.js')
  assert.equal(lock.packages['node_modules/@playwright/test'].dependencies.playwright, playwright.version)
  assert.doesNotMatch(jobBlock(docker, 'validate'), /continue-on-error:/u)
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
  assert.match(jobBlock(docker, 'publish'), /needs:\n      - validate\n/u)
  assert.match(jobBlock(docker, 'publish'), /      - daemon_ci\n/u)
})

test('CI provisions and selects the PostgreSQL fixture client major before validation', () => {
  // Installing a client alone is insufficient: PATH must select every binary from that major.
  const fixture = readFileSync(new URL('scripts/ops/tests/test_reset_database_integration.py', root), 'utf8')
  const major = fixture.match(/^POSTGRES_MAJOR = (\d+)$/m)?.[1]
  assert.ok(major)
  assert.match(fixture, /f"postgres:\{POSTGRES_MAJOR\}-alpine"/u)
  for (const [source, validationName] of [
    [docker, 'Verify Java sources, tests and coverage'],
    [release, 'Verify and build the Node daemon'],
  ]) {
    assert.ok(source.includes(`POSTGRES_VERSION: '${major}'`))
    const setup = source.indexOf('- name: Set up PostgreSQL client tools')
    const selection = source.indexOf('- name: Verify PostgreSQL client selection')
    const scripts = source.indexOf('- name: Run repository script tests')
    const validation = source.indexOf(`- name: ${validationName}`)
    assert.ok(setup > 0 && setup < selection && selection < scripts && scripts < validation)
    const block = source.slice(setup, validation)
    assert.ok(block.includes('https://www.postgresql.org/media/keys/ACCC4CF8.asc'))
    assert.ok(block.includes('URIs: https://apt.postgresql.org/pub/repos/apt'))
    assert.ok(block.includes('Suites: ${VERSION_CODENAME}-pgdg'))
    assert.ok(block.includes('Signed-By: /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc'))
    assert.ok(block.indexOf('/etc/apt/sources.list.d/pgdg.sources') < block.indexOf('sudo apt-get update'))
    assert.doesNotMatch(block, /allow-unauthenticated|trusted=yes/u)
    assert.ok(block.includes('sudo apt-get install --yes --no-install-recommends "postgresql-client-${POSTGRES_VERSION}"'))
    assert.ok(block.includes('echo "/usr/lib/postgresql/${POSTGRES_VERSION}/bin" >> "${GITHUB_PATH}"'))
    assert.ok(block.includes('for tool in psql pg_dump pg_restore createdb pg_isready; do'))
    assert.ok(block.includes('test "$(command -v "${tool}")" = "/usr/lib/postgresql/${POSTGRES_VERSION}/bin/${tool}"'))
  }
})

test('Windows installer gates run both PowerShell hosts and fail closed', () => {
  // Do not use continue-on-error: PS 5.1 is the minimum supported host and PS 7 catches modern drift.
  const block = jobBlock(daemonCi, 'windows_daemon_installer')
  for (const [name, shell, condition] of [
    ['Test installer with Windows PowerShell 5.1', 'powershell', undefined],
    ['Test installer with PowerShell 7', 'pwsh', 'if: ${{ !cancelled() }}'],
  ]) {
    const step = block.match(
      new RegExp(`- name: ${name.replace(/[.()]/g, '\\$&')}\\n([\\s\\S]*?)(?=\\n\\s+- name:|$)`)
    )?.[1]
    assert.ok(step, `missing step: ${name}`)
    assert.match(step, new RegExp(`shell: ${shell}`, 'u'))
    assert.ok(step.includes('run: .\\scripts\\daemon\\tests\\test_daemon_install_windows.ps1'))
    if (condition) {
      assert.ok(step.includes(condition), `${name} must run even after a failed peer`)
    }
  }
  assert.doesNotMatch(daemonCi, /continue-on-error:/u)
  assert.ok(daemonCi.includes('npm --prefix frontend run test:install:windows'))
})

test('the five-platform Node matrix builds and covers every official target', () => {
  // 单个 runner 不能替代平台证据：五个官方平台都必须真跑类型检查、构建与覆盖率。
  const included = [...jobBlock(daemonCi, 'node_daemon').matchAll(/^\s+- os: (\S+)\n\s+platform: (\S+)$/gm)]
    .map((match) => `${match[1]}:${match[2]}`)
  assert.deepEqual(included, [
    'ubuntu-latest:linux-amd64',
    'ubuntu-24.04-arm:linux-arm64',
    'macos-14:macos-arm64',
    'macos-15-intel:macos-amd64',
    'windows-latest:windows-amd64',
  ])
  assert.ok(daemonCi.includes("NODE_VERSION: '24.21.0'"), 'must use the frozen bundled Node version')
  for (const command of ['npm --prefix daemon run check', 'npm --prefix daemon run build', 'npm --prefix daemon run coverage']) {
    assert.ok(jobBlock(daemonCi, 'node_daemon').includes(`run: ${command}`), command)
  }
  const macos = jobBlock(daemonCi, 'node_daemon').match(/if: runner\.os == 'macOS'\n([\s\S]*?)(?=\n\s+- name:|$)/)?.[1]
  assert.ok(macos, 'macOS must run the system-Bash installer/archive contract')
  assert.ok(macos.includes('shell: /bin/bash --noprofile --norc -eo pipefail {0}'))
  assert.ok(macos.includes('/bin/bash --version'))
  assert.ok(macos.includes("python3 -m unittest discover -s scripts/daemon/tests -p 'test_*.py'"))
})

test('release reuses the same daemon matrix and gates on it before staging', () => {
  // tag 发布不得绕过平台矩阵：release 复用 daemon-ci，stage 依赖它与两个安装器宿主门禁成功。
  assert.match(jobBlock(release, 'daemon_ci'), /uses: \.\/\.github\/workflows\/daemon-ci\.yml/u)
  for (const gate of ['validate_daemon', 'validate_windows_daemon', 'daemon_ci']) {
    assert.match(jobBlock(release, 'stage'), new RegExp(`needs:\\n(?:      - \\w+\\n)*      - ${gate}\\n`, 'u'))
  }
  assert.match(jobBlock(release, 'smoke_archives'), /needs: stage\n/u)
  // 五平台归档都必须在对应宿主上真跑烟测。
  const smoke = [...jobBlock(release, 'smoke_archives').matchAll(/platform: (\S+)/g)].map((match) => match[1])
  assert.deepEqual(smoke.sort(), ['linux-amd64', 'linux-arm64', 'macos-amd64', 'macos-arm64', 'windows-amd64'])
})

test('offline upgrade artifact-mode readback reuses the full-verify JAR and stays a required gate', () => {
  // 产物模式必须复用全量 verify 产出的 JAR 路径（版本来自根 POM），且不得退化为跳过或条件步骤。
  const steps = validationSteps()
  const verify = steps.findIndex((step) => stepField(step, 'run') === 'env JAVA_HOME="$JAVA_HOME" mvn -B -ntp verify')
  assert.ok(verify >= 0, 'missing the full Java verify step')
  const matching = steps.filter((step) => stepField(step, 'run')?.includes('-Dkkstudio.upgrade.test.jar='))
  assert.equal(matching.length, 1, 'expected exactly one artifact-mode subprocess step')
  const step = matching[0]
  assert.ok(steps.indexOf(step) > verify, 'the artifact-mode subprocess must run after the full verify')
  const run = stepField(step, 'run')
  assert.match(run, /-pl web -am test/u)
  assert.match(run, /-Dtest=WebApplicationDatabaseUpgradeSubprocessTest/u)
  assert.match(run, /-Dsurefire\.failIfNoSpecifiedTests=false/u)
  assert.match(run, /python3 -c .*pom\.xml.*POM\/4\.0\.0/u)
  assert.match(run, /web\/target\/kk-studio-web-\$\{version\}\.jar/u)
  assert.equal(stepField(step, 'if'), undefined, 'the artifact-mode subprocess must not be conditional')
  assert.doesNotMatch(step, /continue-on-error:|\|\|\s*true|--passWithNoTests|-DskipTests/u)
})
