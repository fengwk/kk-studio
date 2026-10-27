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

function stepsOf(job) {
  return [...job.matchAll(/^      - ([\s\S]*?)(?=^      - |$(?![\s\S]))/gm)].map((match) => match[1])
}

function validationSteps(job = 'validate') {
  // Parse actual step boundaries, not command mentions in comments or other jobs.
  return stepsOf(jobBlock(docker, job))
}

function stepField(step, field, indent = 8) {
  const value = step.match(new RegExp(`^ {${indent}}${field}: (.+)$`, 'm'))?.[1]
    ?? step.match(new RegExp(`^${field}: (.+)$`, 'm'))?.[1]
  if (value !== '|') {
    return value
  }
  const lines = step.match(new RegExp(`^ {${indent}}${field}: \\|\\n((?: {${indent + 2}}.+\\n)+)`, 'm'))?.[1]
  assert.ok(lines, `missing block value for ${field}`)
  return lines.trim().split('\n').map((line) => line.trim()).join('\n')
}

function stepNamed(job, name) {
  const step = validationSteps(job).find((candidate) => stepField(candidate, 'name') === name)
  assert.ok(step, `missing step ${name} in ${job}`)
  return step
}

const PUBLISH_GATES = ['preflight', 'validate', 'validate_frontend', 'daemon_ci']
const MAIN_ONLY_GATES = ['validate', 'validate_frontend', 'daemon_ci']

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

function publishExpected(branch, results) {
  if (results.preflight !== 'success') {
    return false
  }
  if (branch === 'main') {
    return MAIN_ONLY_GATES.every((gate) => results[gate] === 'success')
  }
  return MAIN_ONLY_GATES.every((gate) => results[gate] === 'skipped')
}

test('preflight is unconditional and gates the version, documentation and sensitive-data checks', () => {
  // dev must validate the internal version facts too; the release tag alignment remains in daemon-release.
  const block = jobBlock(docker, 'preflight')
  assert.equal(block.match(/^    if:/m), null)
  const steps = stepsOf(block)
  for (const [name, command] of [
    ['Verify the repository version alignment', 'node scripts/dev/verify/repository/check-version-alignment.mjs'],
    ['Check documentation layout', 'node scripts/dev/verify/repository/check.mjs'],
    ['Scan for sensitive data', 'node scripts/dev/verify/repository/check-sensitive-data.mjs'],
  ]) {
    const step = steps.find((candidate) => stepField(candidate, 'name') === name)
    assert.ok(step, `missing preflight step: ${name}`)
    assert.equal(stepField(step, 'run'), command)
    assert.equal(stepField(step, 'if'), undefined)
    assert.doesNotMatch(step, /RELEASE_TAG:/u)
  }
  for (const gate of MAIN_ONLY_GATES) {
    assert.match(jobBlock(docker, gate), /^ {4}needs: preflight$/m, `${gate} must wait for preflight`)
  }
  assert.match(jobBlock(docker, 'daemon_ci'), /^ {4}needs: preflight$/m)
})

test('push gates stay main-only; explicit manual validation runs all gates on any branch', () => {
  // Evaluate the actual expressions, so changing just one job or accepting push inputs fails.
  assert.match(docker, /validate_only:\n\s+description: .+\n\s+type: boolean\n\s+default: false/u)
  for (const job of MAIN_ONLY_GATES) {
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
  // the group uniform — main needs four successes, other refs need preflight success and three skips.
  const expression = jobCondition(docker, 'publish')
  assert.match(
    jobBlock(docker, 'publish'),
    new RegExp(`needs:\\n${PUBLISH_GATES.map((gate) => `      - ${gate}\\n`).join('')}`, 'u'),
  )
  for (const branch of ['main', 'dev', 'worktree/repair']) {
    for (const gate of PUBLISH_GATES) {
      for (const result of ['success', 'failure', 'cancelled', 'skipped']) {
        for (const cancelled of [false, true]) {
          const baseline = branch === 'main'
            ? Object.fromEntries(PUBLISH_GATES.map((name) => [name, 'success']))
            : { preflight: 'success', ...Object.fromEntries(MAIN_ONLY_GATES.map((name) => [name, 'skipped'])) }
          const results = { ...baseline, [gate]: result }
          for (const validateOnly of [false, true]) {
            assert.equal(
              evaluate(expression, branch, 'workflow_dispatch', validateOnly, { ...results, cancelled }),
              !validateOnly && !cancelled && publishExpected(branch, results),
              `${branch}/${gate}=${result}/cancelled=${cancelled}/validateOnly=${validateOnly}`,
            )
          }
        }
      }
    }
  }
  const allSkipped = Object.fromEntries(PUBLISH_GATES.map((gate) => [gate, 'skipped']))
  assert.equal(evaluate(expression, 'main', 'push', false, {}), true)
  assert.equal(evaluate(expression, 'main', 'push', false, allSkipped), false)
  assert.equal(evaluate(expression, 'dev', 'push', false, { ...allSkipped, preflight: 'success' }), true)
  assert.equal(evaluate(expression, 'dev', 'push', false, { ...allSkipped, preflight: 'failure' }), false)
  assert.equal(evaluate(expression, 'worktree/repair', 'workflow_dispatch', false, { ...allSkipped, preflight: 'success' }), true)
  assert.equal(evaluate(expression, 'dev', 'workflow_dispatch', true, { ...allSkipped, preflight: 'success' }), false)
  assert.equal(evaluate(expression, 'main', 'push', false, { cancelled: true }), false)
})

test('manual validation and image publishing have separate concurrency groups', () => {
  // Validating a branch must not cancel its in-flight image publication.
  const group = docker.match(/^  group: (.+)$/m)?.[1]
  assert.ok(group?.includes('${{ github.ref }}'))
  assert.ok(group?.includes("${{ inputs.validate_only && 'validate' || 'publish' }}"))
})

test('frontend coverage and offline browser suites are required gates using the locked browser', () => {
  // Coverage replaces the unit-test run; every preparation/gate must fail the job on error.
  const steps = validationSteps('validate_frontend')
  const commands = [
    'npm --prefix frontend ci',
    'npm --prefix frontend run lint',
    'npm --prefix frontend run typecheck:test',
    'npm --prefix frontend run coverage',
    'npm --prefix frontend run build',
    'npx --no-install playwright install --with-deps chromium',
    'npm --prefix frontend run test:browser',
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
  assert.doesNotMatch(jobBlock(docker, 'validate_frontend'), /continue-on-error:/u)
})

test('frontend diagnostics survive failed gates and upload only report directories', () => {
  // Exact report paths prevent accidental source/secret uploads; always preserves failure evidence.
  const steps = validationSteps('validate_frontend')
  const uploads = steps.filter((step) => stepField(step, 'uses')?.startsWith('actions/upload-artifact@'))
  assert.equal(uploads.length, 1)
  const upload = uploads[0]
  assert.equal(stepField(upload, 'uses'), 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4')
  assert.equal(stepField(upload, 'if'), '${{ always() }}')
  assert.equal(stepField(upload, 'name', 10), 'frontend-diagnostics')
  const expectedDiagnosticsPath = [
    'frontend/coverage/',
    'reports/browser-site/',
    'reports/layout/',
    'reports/pane/',
    'reports/install-browser/',
    'reports/browser/',
    'reports/composer-editor/',
    'reports/compaction/',
    'reports/subagent-tree/',
    'reports/tool-card/',
  ].join('\n')
  assert.equal(stepField(upload, 'path', 10), expectedDiagnosticsPath)
  assert.equal(stepField(upload, 'retention-days', 10), '7')
  assert.equal(stepField(upload, 'if-no-files-found', 10), 'ignore')
  const browser = steps.findIndex((step) => stepField(step, 'run') === 'npm --prefix frontend run test:browser')
  assert.ok(browser >= 0 && steps.indexOf(upload) > browser)
  const publish = jobBlock(docker, 'publish')
  for (const gate of ['validate', 'validate_frontend', 'daemon_ci']) {
    assert.match(publish, new RegExp(`      - ${gate}\\n`, 'u'))
  }
})

test('CI provisions and selects the PostgreSQL fixture client major before validation', () => {
  // Installing a client alone is insufficient: PATH must select every binary from that major.
  const fixture = readFileSync(new URL('scripts/ops/tests/support/postgres-fixture.mjs', root), 'utf8')
  const major = fixture.match(/^export const POSTGRES_MAJOR = (\d+)$/m)?.[1]
  assert.ok(major)
  assert.ok(fixture.includes('`postgres:${POSTGRES_MAJOR}-alpine`'))
  for (const [source, validationName] of [
    [docker, 'Verify Java sources, tests, coverage and package the distribution JAR'],
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

test('the Canvas/Project schema gate runs in full CI against a pulled isolated image', () => {
  // The checker runs with --pull=never, so CI must pull the pinned image first; both steps are
  // unconditional parts of the full validate job, not the dev-only image publish job.
  const steps = validationSteps()
  const pull = steps.filter((step) => stepField(step, 'run') === 'docker pull postgres:17.10')
  assert.equal(pull.length, 1, 'expected exactly one pinned PostgreSQL image pull')
  const checks = steps.filter(
    (step) => stepField(step, 'run') === 'node scripts/dev/verify/repository/check-canvas-project-schema.mjs',
  )
  assert.equal(checks.length, 1, 'expected exactly one schema checker step')
  const pullIndex = steps.indexOf(pull[0])
  const checkIndex = steps.indexOf(checks[0])
  assert.ok(pullIndex < checkIndex, 'the image pull must precede the schema checker')
  for (const step of [pull[0], checks[0]]) {
    assert.equal(stepField(step, 'if'), undefined, 'schema gate steps must not be conditional')
    assert.doesNotMatch(step, /continue-on-error:|(?:\|\||&&)\s*true/u)
  }
  assert.ok(!jobBlock(docker, 'publish').includes('check-canvas-project-schema.mjs'))
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
  assert.ok(macos.includes('node --test scripts/daemon/tests/*.test.mjs'))
})

test('release reuses the same daemon matrix and gates on it before staging', () => {
  // tag 发布不得绕过平台矩阵：release 复用 daemon-ci，stage 只依赖该矩阵成功。
  assert.match(jobBlock(release, 'daemon_ci'), /uses: \.\/\.github\/workflows\/daemon-ci\.yml/u)
  const stageNeeds = jobBlock(release, 'stage').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
  assert.ok(stageNeeds)
  assert.deepEqual([...stageNeeds.matchAll(/^ {6}- (.+)$/gm)].map((match) => match[1]), ['daemon_ci'])
  for (const removed of ['validate_daemon', 'validate_windows_daemon']) {
    assert.throws(() => jobBlock(release, removed), new RegExp(`missing job ${removed}`, 'u'))
  }
  assert.match(jobBlock(release, 'smoke_archives'), /needs: stage\n/u)
  // 五平台归档都必须在对应宿主上真跑烟测。
  const smoke = [...jobBlock(release, 'smoke_archives').matchAll(/platform: (\S+)/g)].map((match) => match[1])
  assert.deepEqual(smoke.sort(), ['linux-amd64', 'linux-arm64', 'macos-amd64', 'macos-arm64', 'windows-amd64'])
})

test('current startup artifact-mode reuses the distribution JAR and stays a required gate', () => {
  // 产物模式必须复用同一次 distribution verify 产出的 JAR（版本来自根 POM），且不得退化为跳过或条件步骤。
  const steps = validationSteps()
  const verify = steps.findIndex(
    (step) => stepField(step, 'run') === 'env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Pdistribution verify',
  )
  assert.ok(verify >= 0, 'missing the distribution Java verify step')
  assert.ok(!steps.some((step) => stepField(step, 'run') === 'env JAVA_HOME="$JAVA_HOME" mvn -B -ntp verify'))
  const resolve = stepNamed('validate', 'Resolve the distribution Fat JAR')
  assert.equal(stepField(resolve, 'id'), 'distribution')
  assert.match(stepField(resolve, 'run'), /kk-studio-parent/u)
  assert.match(stepField(resolve, 'run'), /web\/target\/kk-studio-web-\$\{version\}\.jar/u)
  const matching = steps.filter((step) => stepField(step, 'run')?.includes('-Dkkstudio.startup.test.jar='))
  assert.equal(matching.length, 1, 'expected exactly one artifact-mode subprocess step')
  const step = matching[0]
  assert.ok(steps.indexOf(step) > verify, 'the artifact-mode subprocess must run after the distribution verify')
  const run = stepField(step, 'run')
  assert.match(run, /-pl web -am test/u)
  assert.match(run, /-Dtest=WebApplicationStartupSubprocessTest/u)
  assert.match(run, /-Dsurefire\.failIfNoSpecifiedTests=false/u)
  assert.match(run, /-Dkkstudio\.startup\.test\.jar=\$\{APP_JAR\}/u)
  assert.equal(stepField(step, 'env', 8), undefined)
  assert.match(step, /APP_JAR: \$\{\{ steps\.distribution\.outputs\.jar \}\}/u)
  assert.equal(stepField(step, 'if'), undefined, 'the artifact-mode subprocess must not be conditional')
  assert.doesNotMatch(step, /continue-on-error:|\|\|\s*true|--passWithNoTests|-DskipTests/u)
})

test('Java update commands are replayed through the built Node decoder as a required gate', () => {
  // Java producer 产物必须由同 job 构建出的生产 Node decoder 真实解码：依赖 daemon 构建，且不得条件化或忽略失败。
  const steps = validationSteps()
  const verify = steps.findIndex(
    (step) => stepField(step, 'run') === 'env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Pdistribution verify',
  )
  assert.ok(verify >= 0, 'missing the distribution Java verify step')
  const matching = steps.filter((step) => stepField(step, 'run')?.includes('scripts/daemon/verify-java-update-commands.mjs'))
  assert.equal(matching.length, 1, 'expected exactly one Java-to-Node update decoder step')
  const step = matching[0]
  const index = steps.indexOf(step)
  assert.ok(index > verify, 'the Java-to-Node decoder step must run after the Java producer verify')
  const install = steps.findIndex((step) => stepField(step, 'run') === 'npm --prefix daemon ci')
  const builds = steps.filter((step) => stepField(step, 'run') === 'npm --prefix daemon run build')
  assert.equal(builds.length, 1, 'expected exactly one same-commit daemon build')
  const build = builds[0]
  const buildIndex = steps.indexOf(build)
  assert.ok(
    install >= 0 && install < buildIndex && buildIndex < verify,
    'install dependencies and build the Node transport before Java integration tests and decoder replay',
  )
  assert.equal(stepField(build, 'if'), undefined, 'the daemon build must not be conditional')
  assert.doesNotMatch(build, /continue-on-error:|\|\|\s*true/u)
  const run = stepField(step, 'run')
  assert.match(run, /node scripts\/daemon\/verify-java-update-commands\.mjs harness\/environment\/target\/daemon-update-java-commands\.json/u)
  assert.equal(stepField(step, 'if'), undefined, 'the Java-to-Node decoder step must not be conditional')
  assert.doesNotMatch(step, /continue-on-error:|\|\|\s*true/u)
})

test('publish reuses only the same-run, same-SHA verified artifact', () => {
  // The producer uploads a closed artifact; the consumer downloads it from this run and verifies it
  // before selecting the Dockerfile's verified-artifact target. No plain verify JAR or latest-run lookup.
  const validate = validationSteps()
  const stage = validate.find((step) => stepField(step, 'name') === 'Stage the verified distribution artifact')
  assert.ok(stage)
  const stageRun = stepField(stage, 'run')
  assert.match(stageRun, /extract-convention4j-agent\.sh/u)
  assert.match(stageRun, /agent="\$\(readlink -f "\$\{agent_alias\}"\)"/u)
  assert.match(stageRun, /--agent "\$\{agent\}"/u)
  assert.match(stageRun, /node scripts\/dev\/ci\/app-artifact\.mjs stage/u)
  assert.match(stageRun, /--source-commit "\$GITHUB_SHA"/u)
  const upload = validate.find((step) => stepField(step, 'name') === 'Upload the verified distribution artifact')
  assert.ok(upload)
  assert.equal(stepField(upload, 'uses'), 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4')
  assert.equal(stepField(upload, 'name', 10), 'kk-studio-app-${{ github.sha }}')
  assert.equal(stepField(upload, 'path', 10), '${{ runner.temp }}/verified-app/')
  assert.equal(stepField(upload, 'if-no-files-found', 10), 'error')

  const publish = stepsOf(jobBlock(docker, 'publish'))
  const download = publish.find((step) => stepField(step, 'name') === 'Download the verified distribution artifact')
  assert.ok(download)
  assert.equal(stepField(download, 'uses'), 'actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093 # v4')
  assert.equal(stepField(download, 'name', 10), 'kk-studio-app-${{ github.sha }}')
  assert.equal(stepField(download, 'if'), "github.ref_name == 'main'")
  assert.doesNotMatch(download, /run-id:/u)
  const verify = publish.find((step) => stepField(step, 'name') === 'Verify the downloaded distribution artifact')
  assert.ok(verify)
  assert.match(stepField(verify, 'run'), /node scripts\/dev\/ci\/app-artifact\.mjs verify/u)
  assert.match(stepField(verify, 'run'), /--source-commit "\$GITHUB_SHA"/u)

  const verified = publish.find((step) => stepField(step, 'name') === 'Build and push linux/amd64 image from the verified artifact')
  const source = publish.find((step) => stepField(step, 'name') === 'Build and push linux/amd64 image from source')
  assert.ok(verified && source)
  assert.equal(stepField(verified, 'if'), "github.ref_name == 'main'")
  assert.equal(stepField(verified, 'context', 10), '${{ runner.temp }}/verified-app')
  assert.equal(stepField(verified, 'target', 10), 'verified-artifact')
  assert.equal(stepField(verified, 'file', 10), '${{ github.workspace }}/deploy/local/Dockerfile')
  assert.equal(stepField(source, 'if'), "github.ref_name != 'main'")
  assert.equal(stepField(source, 'context', 10), '.')
  assert.equal(stepField(source, 'file', 10), 'deploy/local/Dockerfile')
  assert.equal(stepField(source, 'target', 10), undefined)
  for (const step of [verified, source]) {
    assert.equal(stepField(step, 'platforms', 10), 'linux/amd64')
    assert.equal(stepField(step, 'push', 10), 'true')
    assert.match(step, /cache-from: type=gha/u)
    assert.match(step, /cache-to: type=gha,mode=max/u)
  }
  for (const removed of ['validate_unix_daemon', 'validate_windows_daemon']) {
    assert.throws(() => jobBlock(docker, removed), new RegExp(`missing job ${removed}`, 'u'))
  }
})

test('publish uses the pinned Node toolchain before resolving or verifying the artifact', () => {
  // Publish parses the POM and runs the artifact verifier outside a dependency install; the Node
  // major must still be explicit rather than inherited from the runner.
  const steps = stepsOf(jobBlock(docker, 'publish'))
  const setup = steps.find((step) => stepField(step, 'name') === 'Set up Node 24.21')
  assert.ok(setup, 'publish must set up Node explicitly')
  assert.equal(stepField(setup, 'uses'), 'actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4')
  assert.equal(stepField(setup, 'node-version', 10), '${{ env.NODE_VERSION }}')
  assert.equal(stepField(setup, 'cache', 10), undefined)
  assert.equal(stepField(setup, 'cache-dependency-path', 10), undefined)
  const resolve = steps.findIndex((step) => stepField(step, 'name') === 'Resolve the expected distribution version')
  const verify = steps.findIndex((step) => stepField(step, 'name') === 'Verify the downloaded distribution artifact')
  assert.ok(resolve > steps.indexOf(setup), 'setup-node must precede POM version resolution')
  assert.ok(verify > steps.indexOf(setup), 'setup-node must precede artifact verification')
})

test('Windows diagnostic steps stay enabled after failures and remain off Linux and macOS', () => {
  // 两个步骤都使用 always()+Windows：覆盖率失败后仍应采集/上传，其它平台完全不执行。
  const steps = [...jobBlock(daemonCi, 'node_daemon').matchAll(/^      - ([\s\S]*?)(?=^      - |$(?![\s\S]))/gm)]
    .map((match) => match[1])
  const stepNamed = (name) => {
    const step = steps.find((candidate) => (candidate.match(/^ {8}name: (.+)$/m)?.[1]
      ?? candidate.match(/^name: (.+)$/m)?.[1]) === name)
    assert.ok(step, `missing step ${name}`)
    return step
  }
  const condition = (step) => {
    const raw = step.match(/^ {8}if: (.+)$/m)?.[1] ?? step.match(/^if: (.+)$/m)?.[1]
    const expression = raw?.match(/^\$\{\{ (.+) \}\}$/u)?.[1]
    assert.ok(expression, 'expected an evaluated if expression')
    return expression
  }
  const evaluate = (expression, os) => runInNewContext(expression, {
    always: () => true,
    runner: { os },
  }, { timeout: 100 })

  for (const name of ['Collect Windows process exit diagnostics', 'Upload Windows process diagnostic evidence']) {
    const step = stepNamed(name)
    const expression = condition(step)
    assert.equal(evaluate(expression, 'Windows'), true, `${name} must survive failed coverage`)
    assert.equal(evaluate(expression, 'Linux'), false, `${name} must not run on Linux`)
    assert.equal(evaluate(expression, 'macOS'), false, `${name} must not run on macOS`)
  }

  // 探针 flag 只在 Windows 为 1；Linux/macOS 明确为无效值。
  const coverageEnv = stepNamed('Test the daemon with coverage').match(/^ {8}env:\n((?: {10}.+\n)+)/m)?.[1]
  assert.ok(coverageEnv, 'missing coverage env')
  const flag = coverageEnv.match(/^ {10}KK_WINDOWS_NATIVE_DIAGNOSTIC: (.+)$/m)?.[1]
  const flagExpression = flag?.match(/^\$\{\{ (.+) \}\}$/u)?.[1]
  assert.ok(flagExpression, 'missing diagnostic flag expression')
  assert.equal(evaluate(flagExpression, 'Windows'), '1')
  assert.equal(evaluate(flagExpression, 'Linux'), '0')
  assert.equal(evaluate(flagExpression, 'macOS'), '0')
})
