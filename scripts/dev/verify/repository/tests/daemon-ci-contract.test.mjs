import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

/** 仓库根解析：`KK_STUDIO_REPO_ROOT` 优先，否则从本文件位置向上寻找 worktree 根（`.git` 文件或目录）。 */
function repositoryRoot() {
    if (process.env.KK_STUDIO_REPO_ROOT) {
        return path.resolve(process.env.KK_STUDIO_REPO_ROOT)
    }
    let directory = path.dirname(fileURLToPath(import.meta.url))
    for (;;) {
        if (existsSync(path.join(directory, '.git'))) {
            return directory
        }
        const parent = path.dirname(directory)
        if (parent === directory) {
            throw new Error('cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT')
        }
        directory = parent
    }
}

const ROOT = repositoryRoot()
const CI = readFileSync(path.join(ROOT, '.github/workflows/daemon-ci.yml'), 'utf8')
const RELEASE = readFileSync(path.join(ROOT, '.github/workflows/daemon-release.yml'), 'utf8')
const DOCKER = readFileSync(path.join(ROOT, '.github/workflows/docker-publish.yml'), 'utf8')
const PACKAGE = JSON.parse(readFileSync(path.join(ROOT, 'daemon/package.json'), 'utf8'))

/**
 * 结构化解析：按 YAML 的顶层段、job、step、字段边界取值，而不是在整份文件上做字面量匹配。
 * 因此新增步骤、调整顺序、改动矩阵或去掉门禁都会被真实观察到。
 */
function topLevelSection(source, key) {
    const match = source.match(new RegExp(`^${key}:\\n([\\s\\S]*?)(?=^[a-zA-Z]|$(?![\\s\\S]))`, 'm'))
    assert.ok(match, `missing top-level section: ${key}`)
    return match[1]
}

function jobBlock(source, name) {
    const match = source.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  \\w+:|$(?![\\s\\S]))`, 'm'))
    assert.ok(match, `missing job: ${name}`)
    return match[1]
}

function stepsOf(job) {
    return [...job.matchAll(/^      - ([\s\S]*?)(?=^      - |$(?![\s\S]))/gm)].map((match) => match[1])
}

function stepField(step, field, indent = 8) {
    // 一个 step 块的第一行来自 `- ` 之后，因此既可能在 8 空格缩进，也可能在行首。
    return step.match(new RegExp(`^ {${indent}}${field}: (.+)$`, 'm'))?.[1]
        ?? step.match(new RegExp(`^${field}: (.+)$`, 'm'))?.[1]
        ?? step.match(new RegExp(`^ +${field}: (.+)$`, 'm'))?.[1]
}

function stepRun(step) {
    const value = stepField(step, 'run')
    assert.equal(value, '|', 'expected a block-form run step')
    const marker = '        run: |\n'
    const start = step.indexOf(marker)
    assert.ok(start >= 0, 'missing run block')
    return step.slice(start + marker.length).replace(/\n+$/, '')
}

function stepEnv(step, key) {
    const env = step.match(/^ {8}env:\n((?: {10}.+\n)+)/m)?.[1]
    assert.ok(env, 'missing env block')
    const value = env.match(new RegExp(`^ {10}${key}: (.+)$`, 'm'))?.[1]
    assert.ok(value, `missing env value: ${key}`)
    return value
}

const WINDOWS_DIAGNOSTIC_STEPS = new Set([
    'Collect Windows process exit diagnostics',
    'Upload Windows process diagnostic evidence',
])

function stepNamed(source, job, name) {
    const step = stepsOf(jobBlock(source, job)).find((candidate) => stepField(candidate, 'name') === name)
    assert.ok(step, `missing step: ${name}`)
    return step
}

function dedent(block, width) {
    const prefix = ' '.repeat(width)
    return block.split('\n').map((line) => (line.startsWith(prefix) ? line.slice(width) : line)).join('\n')
}

test('daemon-ci runs the full Node daemon suite on the five release targets', () => {
    // 平台条件用例由各用例自己决定是否执行；矩阵必须覆盖五个发行目标，并且不整体跳过 native 套件。
    const job = jobBlock(CI, 'node_daemon')
    const runners = [...job.matchAll(/^ {10}- os: (\S+)\n {12}platform: (\S+)$/gm)]
        .map((match) => `${match[2]}@${match[1]}`)
    assert.deepEqual(runners, [
        'linux-amd64@ubuntu-latest',
        'linux-arm64@ubuntu-24.04-arm',
        'macos-arm64@macos-14',
        'macos-amd64@macos-15-intel',
        'windows-amd64@windows-latest',
    ])
    assert.doesNotMatch(job, /fail-fast: true/u)

    const steps = stepsOf(job)
    const runs = steps.map((step) => stepField(step, 'run'))
    let previous = -1
    for (const command of [
        'npm --prefix daemon ci',
        'npm --prefix daemon run check',
        'npm --prefix daemon run build',
        'npm --prefix daemon run coverage',
    ]) {
        const index = runs.indexOf(command)
        assert.ok(index > previous, `missing or mistimed daemon step: ${command}`)
        previous = index
        const step = steps[index]
        assert.equal(stepField(step, 'if'), undefined, `${command} must not be conditional`)
        assert.doesNotMatch(step, /continue-on-error:|--passWithNoTests/u)
    }
    // 构建必须先于覆盖率。macOS 安装器烟测可以跟在覆盖率之后，但不能插到构建之前。
    const coverage = runs.indexOf('npm --prefix daemon run coverage')
    assert.ok(coverage > runs.indexOf('npm --prefix daemon run build'))
    const trailing = steps.slice(coverage + 1)
    for (const step of trailing) {
        // 常规 Windows 采集/上传显式保留 always+Windows 语义；其余尾部步骤仍然只在 macOS 执行。
        if (WINDOWS_DIAGNOSTIC_STEPS.has(stepField(step, 'name'))) {
            assert.equal(stepField(step, 'if'), '${{ always() && runner.os == \'Windows\' }}')
        } else {
            assert.equal(stepField(step, 'if'), "runner.os == 'macOS'")
        }
    }
})

test('every daemon coverage gate builds the tested JavaScript first', () => {
    // Daemon 的 Vitest alias 读取 dist；仅 check 不会生成真实被测代码。覆盖率只在 daemon-ci
    // 矩阵执行，release stage 只构建发行 JS 并运行仓库脚本测试。
    for (const [source, job] of [[CI, 'node_daemon']]) {
        const commands = stepsOf(jobBlock(source, job))
            .map((step) => stepField(step, 'run') === '|' ? stepRun(step) : stepField(step, 'run') ?? '')
            .join('\n')
        const build = commands.indexOf('npm --prefix daemon run build')
        const coverage = commands.indexOf('npm --prefix daemon run coverage')
        assert.ok(build >= 0 && coverage > build, `${job} must build before coverage`)
    }
})

test('Node daemon release and installer jobs do not require a JDK toolchain', () => {
    // 发行 fixture 已使用 Node/tar，不再需要退役 Java Daemon 的 jar 工具。
    assert.doesNotMatch(RELEASE, /actions\/setup-java@|JAVA_VERSION/u)
    for (const name of ['validate_unix_daemon', 'validate_windows_daemon', 'validate_daemon']) {
        assert.throws(() => jobBlock(DOCKER, name), new RegExp(`missing job: ${name}`, 'u'))
        assert.throws(() => jobBlock(RELEASE, name), new RegExp(`missing job: ${name}`, 'u'))
    }
})

test('daemon-ci is a pull-request and reusable gate without a duplicate push trigger', () => {
    const triggers = topLevelSection(CI, 'on')
    assert.match(triggers, /^ {2}workflow_call:$/m)
    assert.match(triggers, /^ {2}workflow_dispatch:$/m)
    assert.doesNotMatch(triggers, /^ {2}push:$/m, 'docker-publish and daemon-release own the main/tag invocations')
    const pullRequest = triggers.match(/^ {2}pull_request:\n([\s\S]*?)(?=^ {2}\w)/m)?.[1]
    assert.ok(pullRequest, 'missing pull_request trigger')
    assert.match(pullRequest, /^ {4}paths:$/m)
    assert.match(pullRequest, /daemon\/\*\*/u)
    assert.match(topLevelSection(CI, 'permissions'), /^ {2}contents: read$/m)
    assert.match(topLevelSection(CI, 'concurrency'), /^ {2}cancel-in-progress: true$/m)

    const setup = stepsOf(jobBlock(CI, 'node_daemon'))
        .find((step) => stepField(step, 'uses')?.startsWith('actions/setup-node@'))
    assert.ok(setup, 'missing setup-node step')
    assert.equal(stepField(setup, 'cache', 10), 'npm')
    assert.equal(stepField(setup, 'cache-dependency-path', 10), 'daemon/package-lock.json')
})

test('the daemon matrix concurrency group isolates the calling workflow', () => {
    // group 必须同时绑定 caller workflow 与 ref：daemon-ci 既独立运行，也被 daemon-release /
    // docker-publish 复用，只按 ref 分组会让两个 caller 在同一个 main 上互相取消共享矩阵。
    // 同一 caller 的相同 ref 必须稳定落入同一组，cancel-in-progress 才能覆盖旧 revision。
    const group = topLevelSection(CI, 'concurrency').match(/^ {2}group: (.+)$/m)?.[1]
    assert.ok(group, 'missing concurrency group')
    assert.match(group, /\$\{\{ github\.workflow \}\}/u, 'the group must bind the caller workflow')
    const resolve = (workflow, ref) => group
        .replace('${{ github.workflow }}', workflow)
        .replace('${{ github.ref }}', ref)

    const ref = 'refs/heads/main'
    const callers = ['daemon-ci', 'daemon-release', 'docker-publish']
    const groups = callers.map((workflow) => resolve(workflow, ref))
    assert.equal(new Set(groups).size, 3, 'each calling workflow must occupy a distinct group')

    assert.equal(resolve('daemon-release', ref), resolve('daemon-release', ref))
    assert.notEqual(resolve('daemon-release', ref), resolve('daemon-release', 'refs/heads/dev'))
})

test('the coverage step matches the daemon package scripts and provider', () => {
    // 覆盖率必须是仓库里真实配置的能力：脚本自身启用 --coverage，provider 是已安装的 devDependency。
    assert.match(PACKAGE.scripts.coverage, /vitest run --coverage/u)
    assert.match(PACKAGE.scripts.build, /tsc -p tsconfig\.json/u)
    assert.equal(typeof PACKAGE.devDependencies['@vitest/coverage-v8'], 'string')
    assert.equal(
        stepField(stepNamed(CI, 'node_daemon', 'Test the daemon with coverage'), 'run'),
        'npm --prefix daemon run coverage',
    )
})

test('the release pipeline smoke-tests every staged archive on its own host', () => {
    // 五个归档必须在对应主机加载，发布 job 不能在 Linux 上代替其它平台验收。
    const smokeJob = jobBlock(RELEASE, 'smoke_archives')
    const targets = [...smokeJob.matchAll(/^ {10}- os: (\S+)\n {12}platform: (\S+)$/gm)]
        .map((match) => `${match[2]}@${match[1]}`)
    assert.deepEqual(targets, [
        'linux-amd64@ubuntu-latest',
        'linux-arm64@ubuntu-24.04-arm',
        'macos-arm64@macos-14',
        'macos-amd64@macos-15-intel',
        'windows-amd64@windows-latest',
    ])
    const smoke = stepNamed(RELEASE, 'smoke_archives', 'Smoke-test this host archive')
    assert.equal(stepField(smoke, 'if'), undefined)
    const run = stepRun(smoke)
    assert.match(run, /scripts\/daemon\/smoke-archive\.sh/u)
    assert.match(run, /scripts\/daemon\/smoke-archive\.ps1/u)
    assert.match(run, /matrix\.platform/u)
    const publishNeeds = jobBlock(RELEASE, 'publish').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
    assert.match(publishNeeds, /^ {6}- smoke_archives$/m)
    assert.match(publishNeeds, /^ {6}- stage$/m)
    const stageNeeds = jobBlock(RELEASE, 'stage').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
    assert.match(stageNeeds, /^ {6}- daemon_ci$/m)
    assert.doesNotMatch(stageNeeds, /validate_daemon|validate_windows_daemon/u)

    // 烟测入口必须真实使用归档自带的冻结运行时，并在不含用户 Node/Java 的 PATH 下调用。
    const smokeScript = readFileSync(path.join(ROOT, 'scripts/daemon/smoke-archive.sh'), 'utf8')
    assert.match(smokeScript, /runtime\/code-server\/lib\/node/u)
    assert.match(smokeScript, /env -i PATH=/u)
    assert.match(smokeScript, /command -v node/u)
    assert.match(smokeScript, /command -v java/u)
    for (const marker of ['--version', '--help', '--check-config']) {
        assert.ok(smokeScript.includes(marker), `the smoke must exercise ${marker}`)
    }
    assert.match(smokeScript, /koffi/u)
    assert.match(smokeScript, /acquireDirectoryLock/u)
    assert.match(smokeScript, /Darwin/u)
    assert.match(smokeScript, /out\/node\/entry\.js/u)
    assert.match(smokeScript, /\.node/u)
    assert.match(smokeScript, /only runs on Linux or macOS/u)
    assert.doesNotMatch(smokeScript, /only runs on Linux;/u)
})

test('the release workflow can dry-run every platform without ever publishing', () => {
    // 手动触发只做候选验收：完整跑 stage 与五平台烟测，但 publish 必须被跳过。
    const triggers = topLevelSection(RELEASE, 'on')
    assert.match(triggers, /^ {2}workflow_dispatch:$/m)
    const input = triggers.match(/^ {6}tag:\n((?: {8}.*\n)+)/m)?.[1]
    assert.ok(input, 'missing workflow_dispatch tag input')
    assert.match(input, /^ {8}type: string$/m)
    assert.match(input, /^ {8}required: true$/m)

    // input 只经全局 env 传递，绝不直接拼进 shell。
    assert.match(
        topLevelSection(RELEASE, 'env'),
        /^ {2}RELEASE_TAG: \$\{\{ inputs\.tag \|\| github\.ref_name \}\}$/m,
    )

    assert.match(
        jobBlock(RELEASE, 'publish'),
        /^ {4}if: \$\{\{ github\.event_name == 'push' && startsWith\(github\.ref, 'refs\/tags\/'\) \}\}$/m,
    )
    // stage 与烟测矩阵不因手动触发而被 if 跳过。
    for (const job of ['stage', 'smoke_archives']) {
        assert.equal(jobBlock(RELEASE, job).match(/^ {4}if:/m), null, `${job} must not be conditional`)
    }
})

test('the release workflow is read-only except for the publishing job', () => {
    // 默认最小权限；只有真正写 Release 的 job 才要 contents: write。
    assert.match(topLevelSection(RELEASE, 'permissions'), /^ {2}contents: read$/m)
    assert.match(jobBlock(RELEASE, 'publish'), /^ {4}permissions:\n {6}contents: write$/m)
    for (const job of ['stage', 'smoke_archives']) {
        assert.doesNotMatch(jobBlock(RELEASE, job), /^ {4}permissions:\n {6}contents: write$/m, `${job} must not gain write access`)
    }
})

test('the release commit gate keeps the tag ancestry rule and rejects other events', () => {
    const run = stepRun(stepNamed(RELEASE, 'stage', 'Require a safe release tag on an approved commit'))
    assert.match(run, /^ {10}tag="\$\{RELEASE_TAG\}"$/m)
    assert.match(run, /\^v\[0-9A-Za-z\._-\]\+\$/u)
    assert.match(run, /case "\$\{GITHUB_EVENT_NAME\}" in/u)
    // 正式 tag push 仍要求提交在 origin/main 上。
    assert.match(run, /push\)[\s\S]*?git merge-base --is-ancestor "\$\{commit\}" refs\/remotes\/origin\/main/u)
    // 手动 dry run 只要求候选提交包含 origin/main。
    assert.match(run, /workflow_dispatch\)[\s\S]*?git merge-base --is-ancestor refs\/remotes\/origin\/main "\$\{commit\}"/u)
    // 其它事件显式失败。
    assert.match(run, /\*\)\n {14}echo "unsupported release event/u)
})

test('the release commit gate really enforces main lineage and safe tags when executed', () => {
    // 在临时 git 仓库里真实执行嵌入 stage 的 gate 脚本：观察退出码，而不是只匹配字符串。
    const script = dedent(
        stepRun(stepNamed(RELEASE, 'stage', 'Require a safe release tag on an approved commit')),
        10,
    )
    const repo = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-release-gate-'))
    const git = (...args) => {
        const result = spawnSync('git', args, { cwd: repo, encoding: 'utf8' })
        assert.equal(result.status, 0, `git ${args.join(' ')} failed: ${result.stderr}`)
        return result.stdout.trim()
    }
    const commit = (file, content, message) => {
        writeFileSync(path.join(repo, file), content)
        git('add', file)
        git('commit', '-q', '-m', message)
        return git('rev-parse', 'HEAD')
    }
    try {
        git('init', '-q')
        git('config', 'user.name', 'release-gate')
        git('config', 'user.email', 'release-gate@example.com')
        // origin/main 指向 main 的 tip；gate 只读这个远程跟踪 ref。
        const base = commit('f.txt', 'base\n', 'base')
        const mainTip = commit('f.txt', 'main\n', 'main')
        git('update-ref', 'refs/remotes/origin/main', mainTip)
        // 已合入 main 之后的候选提交：包含 origin/main，但自身不在 main 上。
        const ahead = commit('f.txt', 'ahead\n', 'ahead')
        // 与 main 分叉的提交：从 base 分出，既不包含 main tip，也不在其上。
        git('checkout', '-q', '-f', '--detach', base)
        const divergent = commit('g.txt', 'divergent\n', 'divergent')
        // 孤立提交：与 main 没有共同祖先。
        git('checkout', '-q', '--orphan', 'orphan')
        git('rm', '-r', '-q', '--cached', '.')
        git('commit', '-q', '--allow-empty', '-m', 'orphan')
        const orphan = git('rev-parse', 'HEAD')

        const gate = (event, tag, head) => {
            git('checkout', '-q', '-f', '--detach', head)
            const output = path.join(repo, 'github-output')
            writeFileSync(output, '')
            const result = spawnSync('bash', ['-s'], {
                input: `${script}\n`,
                cwd: repo,
                env: {
                    ...process.env,
                    GITHUB_EVENT_NAME: event,
                    RELEASE_TAG: tag,
                    GITHUB_OUTPUT: output,
                },
                encoding: 'utf8',
            })
            return {
                status: result.status,
                stderr: result.stderr,
                output: readFileSync(output, 'utf8'),
            }
        }

        // push：提交必须在 origin/main 上；main tip 通过，未合入/分叉/孤立都被拒绝。
        const onMain = gate('push', 'v1.2.3', mainTip)
        assert.equal(onMain.status, 0, onMain.stderr)
        assert.match(onMain.output, /^commit=[0-9a-f]{40}$/m)
        assert.match(onMain.output, /^source_date_epoch=\d+$/m)
        for (const [label, head] of [['ahead', ahead], ['divergent', divergent], ['orphan', orphan]]) {
            const rejected = gate('push', 'v1.2.3', head)
            assert.notEqual(rejected.status, 0, `push must reject a ${label} commit`)
            assert.match(rejected.stderr, /not an ancestor of origin\/main/u)
        }

        // workflow_dispatch：候选必须包含 origin/main；包含它的候选通过，分叉/孤立被拒绝。
        const manual = gate('workflow_dispatch', 'v1.2.3', ahead)
        assert.equal(manual.status, 0, manual.stderr)
        assert.match(manual.output, /^commit=[0-9a-f]{40}$/m)
        for (const [label, head] of [['divergent', divergent], ['orphan', orphan]]) {
            const rejected = gate('workflow_dispatch', 'v1.2.3', head)
            assert.notEqual(rejected.status, 0, `manual dry run must reject a ${label} commit`)
            assert.match(rejected.stderr, /does not contain origin\/main/u)
        }

        // 不安全 tag 与未知事件即使提交合法也必须显式失败。
        assert.notEqual(gate('push', 'v1.2.3;rm -rf /', mainTip).status, 0, 'an unsafe tag must be rejected')
        assert.notEqual(gate('push', 'v1.2.3$(id)', mainTip).status, 0, 'shell metacharacters must be rejected')
        assert.notEqual(gate('pull_request', 'v1.2.3', mainTip).status, 0, 'an unsupported event must be rejected')
    } finally {
        rmSync(repo, { recursive: true, force: true })
    }
})

test('linux-amd64 smoke installs native broker host libraries without skipping native modules', () => {
    // headless runner 缺 MSAL native broker 依赖的宿主库，只在 linux-amd64 补装。
    const step = stepNamed(RELEASE, 'smoke_archives', 'Install Linux native broker host libraries')
    assert.equal(stepField(step, 'if'), "matrix.platform == 'linux-amd64'")
    const run = stepRun(step)
    assert.match(run, /apt-get install --yes --no-install-recommends libwebkit2gtk-4\.1-0 libsecret-1-0/u)

    // 这些宿主库不是跳过原生模块的借口：.node 仍被逐个 require，没有认证/MSAL 豁免。
    const smokeScript = readFileSync(path.join(ROOT, 'scripts/daemon/smoke-archive.sh'), 'utf8')
    assert.match(smokeScript, /for \(const modulePath of found\) \{\n {2}require\(modulePath\)\n/u)
    assert.doesNotMatch(smokeScript, /msal|microsoft-authentication/iu)

    // 依赖安装必须发生在烟测步骤之前。
    const steps = stepsOf(jobBlock(RELEASE, 'smoke_archives'))
    const install = steps.findIndex((candidate) => stepField(candidate, 'name') === 'Install Linux native broker host libraries')
    const smoke = steps.findIndex((candidate) => stepField(candidate, 'name') === 'Smoke-test this host archive')
    assert.ok(install >= 0 && smoke > install, 'native host libraries must be installed before the smoke')
})

test('the Windows archive smoke extracts with the absolute system tar', () => {
    // 经 bash 启动时继承的 PATH 会命中 Git 的 GNU tar，它把 C:\ 当成远程主机路径。
    const smoke = readFileSync(path.join(ROOT, 'scripts/daemon/smoke-archive.ps1'), 'utf8')
    assert.match(smoke, /\$tar = Join-Path \$env:SystemRoot 'System32\\tar\.exe'/u)
    assert.match(smoke, /if \(-not \(Test-Path -LiteralPath \$tar -PathType Leaf\)\)/u)
    assert.match(smoke, /& \$tar -xzf \$Archive -C \$root/u)
    const extraction = smoke.split('\n').filter((line) => line.includes('-xzf'))
    assert.equal(extraction.length, 1, 'there must be exactly one extraction invocation')
    assert.equal(extraction[0].trim(), '& $tar -xzf $Archive -C $root', 'extraction must not fall back to a bare PATH tar')
})

test('daemon-ci runs the installer and archive smoke on the macOS runner', () => {
    const step = stepNamed(CI, 'node_daemon', 'Test the macOS installer and archive smoke with system Bash')
    assert.equal(stepField(step, 'if'), "runner.os == 'macOS'")
    assert.match(step, /\/bin\/bash --noprofile --norc/u)
    const run = stepRun(step)
    assert.match(run, /node --test scripts\/daemon\/tests\/\*\.test\.mjs/u)
    assert.match(run, /\/bin\/bash --version/u)
})

test('the release pipeline reuses the same matrix and cannot publish without it', () => {
    const jobs = [...RELEASE.matchAll(/^ {2}([A-Za-z0-9_]+):\n([\s\S]*?)(?=^ {2}\w+:|$(?![\s\S]))/gm)]
        .map(([, name, block]) => ({ name, block }))
    const reused = jobs.find((job) => /^ {4}uses: \.\/\.github\/workflows\/daemon-ci\.yml$/m.test(job.block))
    assert.ok(reused, 'daemon-release must reuse the daemon-ci matrix')

    const needs = jobBlock(RELEASE, 'stage').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
    assert.ok(needs, 'missing stage needs')
    assert.match(needs, new RegExp(`^ {6}- ${reused.name}$`, 'm'))
    assert.doesNotMatch(needs, /validate_daemon|validate_windows_daemon/u)
    const publishNeeds = jobBlock(RELEASE, 'publish').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
    assert.match(publishNeeds, /^ {6}- smoke_archives$/m)
    assert.match(topLevelSection(RELEASE, 'on'), /^ {4}tags:$/m)
})

test('full repository validation reuses the daemon matrix and installs archive parsing dependencies first', () => {
    // 候选分支 validate_only 必须真实调用五平台矩阵，不能仅在输入描述里承诺。
    const matrix = jobBlock(DOCKER, 'daemon_ci')
    assert.match(matrix, /uses: \.\/\.github\/workflows\/daemon-ci\.yml/u)
    assert.match(matrix, /inputs\.validate_only/u)
    const publish = jobBlock(DOCKER, 'publish')
    assert.match(publish, /^ {6}- daemon_ci$/m)
    assert.match(publish, /needs\.daemon_ci\.result == 'success'/u)
    for (const [source, job] of [[DOCKER, 'validate'], [RELEASE, 'stage']]) {
        const steps = stepsOf(jobBlock(source, job))
        const install = steps.findIndex((step) => stepField(step, 'run') === 'npm --prefix daemon ci')
        const scriptTests = steps.findIndex((step) => stepField(step, 'name') === 'Run repository script tests')
        assert.ok(install >= 0 && scriptTests > install, `${job} must install the real tar parser before fixtures`)
    }
})

test('the release version gate reads real project files and fails on any drift', () => {
    const step = stepNamed(RELEASE, 'stage', 'Verify the repository version alignment')
    assert.doesNotMatch(stepField(step, 'name'), /\d+\.\d+\.\d+/u, 'the gate must not pin a version in its name')
    // 版本来自真实文件，由仓库脚本读取 pom.xml 的元素与属性、daemon/package.json 与 tag；工作流不写死任何版本。
    assert.equal(
        dedent(stepRun(step), 10).trim(),
        'set -euo pipefail\nnode scripts/dev/verify/repository/check-version-alignment.mjs',
    )
    const gate = readFileSync(path.join(ROOT, 'scripts/dev/verify/repository/check-version-alignment.mjs'), 'utf8')
    assert.match(gate, /daemon\/package\.json/u)
    assert.match(gate, /RELEASE_TAG/u)
    assert.doesNotMatch(gate, /["']\d+\.\d+\.\d+["']/u, 'the gate must not hardcode a version')

    const runGate = (fixturePath, tag) => spawnSync('node', [path.join(ROOT, 'scripts/dev/verify/repository/check-version-alignment.mjs')], {
        cwd: fixturePath,
        env: { ...process.env, RELEASE_TAG: tag, KK_STUDIO_REPO_ROOT: fixturePath },
        encoding: 'utf8',
    })

    const fixture = (version, propertyVersion = version, packageVersion = version) => {
        const directory = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-version-gate-'))
        mkdirSync(path.join(directory, 'daemon'), { recursive: true })
        writeFileSync(path.join(directory, 'pom.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>fun.fengwk.kk-studio</groupId>
    <artifactId>kk-studio-parent</artifactId>
    <version>${version}</version>
    <properties>
        <kk-studio.version>${propertyVersion}</kk-studio.version>
    </properties>
</project>
`)
        writeFileSync(
            path.join(directory, 'daemon/package.json'),
            `${JSON.stringify({ name: '@kk-studio/daemon', version: packageVersion }, null, 2)}\n`,
        )
        return directory
    }

    const aligned = fixture('2.3.4')
    try {
        const alignedResult = runGate(aligned, 'v2.3.4')
        assert.equal(alignedResult.status, 0, `${alignedResult.stdout}\n${alignedResult.stderr}`)
        assert.match(alignedResult.stdout, /2\.3\.4/u)
        // tag 不带 v 前缀同样必须通过：对齐不依赖 tag 形式。
        assert.equal(runGate(aligned, '2.3.4').status, 0)

        const driftedTag = runGate(aligned, 'v2.3.5')
        assert.equal(driftedTag.status, 1, 'a tag that disagrees with the project version must fail')
        assert.match(driftedTag.stderr, /alignment failed/u)
        assert.match(driftedTag.stderr, /2\.3\.5/u)

        for (const [label, propertyVersion, packageVersion] of [
            ['kk-studio.version drift', '2.3.5', '2.3.4'],
            ['daemon package drift', '2.3.4', '2.3.5'],
        ]) {
            const directory = fixture('2.3.4', propertyVersion, packageVersion)
            try {
                const result = runGate(directory, 'v2.3.4')
                assert.equal(result.status, 1, `${label} must fail`)
                assert.match(result.stderr, /alignment failed/u, label)
            } finally {
                rmSync(directory, { recursive: true, force: true })
            }
        }

        // 属性缺失必须显式失败，不能被当成「没有可比较的版本」而静默通过。
        const missing = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-version-gate-'))
        mkdirSync(path.join(missing, 'daemon'), { recursive: true })
        writeFileSync(
            path.join(missing, 'pom.xml'),
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            + '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>2.3.4</version></project>\n',
        )
        writeFileSync(path.join(missing, 'daemon/package.json'), '{"name":"@kk-studio/daemon","version":"2.3.4"}\n')
        try {
            const result = runGate(missing, 'v2.3.4')
            assert.equal(result.status, 1, 'a missing kk-studio.version property must fail')
            assert.match(result.stderr, /kk-studio\.version/u)
        } finally {
            rmSync(missing, { recursive: true, force: true })
        }

        // 真实仓库必须自洽：用 daemon 包版本推导 tag，证明 POM 与包版本没有漂移。
        const real = runGate(ROOT, `v${PACKAGE.version}`)
        assert.equal(real.status, 0, `${real.stdout}\n${real.stderr}`)
    } finally {
        rmSync(aligned, { recursive: true, force: true })
    }
})

test('the Windows installer launches main.js directly and its fixture stays inside the sandbox', () => {
    const installer = readFileSync(path.join(ROOT, 'scripts/daemon/install.ps1'), 'utf8')
    assert.match(
        installer,
        /Invoke-NativeProcess -Executable \$node `\r?\n\s+-Arguments @\("main\.js", "--version"\) `\r?\n\s+-WorkingDirectory \$script:StagedLib/u,
    )
    assert.match(installer, /\$arguments = @\("main\.js", "--check-config", \$stagedConfig\)/u)
    assert.match(
        installer,
        /\$script:HomePath =\r?\n\s+\[Environment\]::GetFolderPath\(\[Environment\+SpecialFolder\]::UserProfile\)/u,
        'production host resolution must not accept a USERPROFILE bypass',
    )
    assert.match(installer, /\[System\.Security\.Principal\.WindowsIdentity\]::GetCurrent\(\)/u)

    // 只执行 fixture 中的 Node stub，不启动 PowerShell，也不注册计划任务。
    const fixture = readFileSync(path.join(ROOT, 'scripts/daemon/tests/test_daemon_install_windows.ps1'), 'utf8')
    assert.doesNotMatch(fixture, /\$ProcessOnly/u)
    assert.match(
        fixture,
        /\$repoRoot = Split-Path -Parent \(Split-Path -Parent \(Split-Path -Parent \$PSScriptRoot\)\)/u,
        'repoRoot must climb tests -> daemon -> scripts -> repository root',
    )
    const heredoc = fixture.match(/\$stubMain = @'\r?\n([\s\S]*?)\r?\n'@/u)
    assert.ok(heredoc, 'missing stubMain heredoc')
    const stub = heredoc[1]
    assert.match(stub, /import fs from "node:fs"/u)
    assert.doesNotMatch(stub, /\brequire\(/u)
    assert.match(
        fixture,
        /\. Import-Installer -Parameters @\{ Command = "help" \}\r?\n\s+Initialize-HostContext\r?\n[\s\S]*?\r?\n\s+Assert-DirectArgumentRoundTrip/u,
    )
    assert.match(fixture, /\$acl = New-PrivateAcl\r?\n\s+Set-Acl -LiteralPath \$configPath -AclObject \$acl/u)
    assert.match(fixture, /\$script:HomePath = \$profileDir/u)
    const importInstaller = fixture.match(/function Import-Installer \{[\s\S]*?\n\}/u)?.[0] ?? ''
    assert.match(importInstaller, /param\(\[hashtable\] \$Parameters\)/u)
    assert.match(importInstaller, /\. \$installer @Parameters/u)
    const invokeInstaller = fixture.match(/function Invoke-Installer \{[\s\S]*?\n\}/u)?.[0] ?? ''
    assert.doesNotMatch(invokeInstaller, /\$script:Tasks = @\{\}/u)
    assert.match(invokeInstaller, /param\(\[hashtable\] \$Parameters\)/u)
    assert.match(invokeInstaller, /\. Import-Installer -Parameters \$Parameters/u)
    assert.match(fixture, /Invoke-Installer -Parameters @\{ Command = "status" \}/u)
    assert.match(fixture, /Invoke-Installer -Parameters @\{ Command = "help" \}/u)
    assert.match(fixture, /Invoke-Installer -Parameters @\{ Command = "uninstall" \}/u)
    assert.match(fixture, /ConfigFile = \$pairs\[0\]; TokenFile = \$pairs\[1\]/u)
    assert.match(fixture, /JavaHome = "\/opt\/jdk"/u)
    assert.doesNotMatch(fixture, /\. \$installer status/u)
    assert.doesNotMatch(fixture, /@Arguments/u)

    const directory = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-windows-stub-'))
    try {
        writeFileSync(path.join(directory, 'package.json'), '{"type":"module"}\n')
        writeFileSync(path.join(directory, 'main.js'), `${stub}\n`)
        writeFileSync(path.join(directory, 'daemon.json'), '{"studioUrl":"https://studio.example.com"}\n')
        const entry = path.join(directory, 'main.js')
        const version = spawnSync(process.execPath, [entry, '--version'], { encoding: 'utf8' })
        assert.equal(version.status, 0, version.stderr)
        assert.equal(version.stdout, 'kk-studio-daemon 1.0.14\n')
        const config = spawnSync(process.execPath, [entry, '--check-config', path.join(directory, 'daemon.json')], {
            encoding: 'utf8',
        })
        assert.equal(config.status, 0, config.stderr)
        assert.equal(config.stdout, 'Daemon configuration is valid\n')
        const invalid = spawnSync(process.execPath, [entry, '--check-config', path.join(directory, 'missing.json')], {
            encoding: 'utf8',
        })
        assert.equal(invalid.status, 2)
        const wrappedPath = path.join(directory, 'wrapped.json')
        writeFileSync(wrappedPath, '{"daemon":{"studioUrl":"https://studio.example.com"}}\n')
        const wrapped = spawnSync(process.execPath, [entry, '--check-config', wrappedPath], { encoding: 'utf8' })
        assert.equal(wrapped.status, 2)
        assert.match(wrapped.stderr, /Invalid daemon configuration: daemon\.daemon: unknown field/u)
    } finally {
        rmSync(directory, { recursive: true, force: true })
    }
})

test('regular Windows coverage automatically enables the exit-source probe with a fixed label', () => {
    // 采集只改变 Windows 的测试环境变量，覆盖率命令与无条件门禁语义保持不变。
    const coverage = stepNamed(CI, 'node_daemon', 'Test the daemon with coverage')
    assert.equal(stepField(coverage, 'id'), 'coverage')
    assert.equal(stepField(coverage, 'run'), 'npm --prefix daemon run coverage')
    assert.equal(stepField(coverage, 'if'), undefined)
    assert.equal(stepEnv(coverage, 'KK_WINDOWS_NATIVE_DIAGNOSTIC'), "${{ runner.os == 'Windows' && '1' || '0' }}")
    assert.equal(stepEnv(coverage, 'KK_WINDOWS_NATIVE_DIAGNOSTIC_LABEL'), "${{ runner.os == 'Windows' && 'regular-coverage' || 'disabled' }}")
    assert.match(
        stepEnv(coverage, 'KK_WINDOWS_NATIVE_DIAGNOSTIC_OUTPUT'),
        /runner\.os == 'Windows'[\s\S]*format\('\{0\}\/reports\/windows-process-diagnostics', github\.workspace\)[\s\S]*''/u,
    )

    const job = jobBlock(CI, 'node_daemon')
    const steps = stepsOf(job)
    const toolchain = steps.find((step) => stepField(step, 'name') === 'Record Windows diagnostic toolchain and window start')
    assert.ok(toolchain, 'missing Windows toolchain step')
    assert.equal(stepField(toolchain, 'if'), "runner.os == 'Windows'")
    assert.ok(steps.indexOf(toolchain) < steps.findIndex((step) => stepField(step, 'run') === 'npm --prefix daemon ci'))
    const run = stepRun(toolchain)
    assert.match(run, /window-start\.txt/u)
    assert.match(run, /sourceCommit/u)
    assert.match(run, /runnerOS/u)
    assert.match(run, /imageOS/u)
    assert.match(run, /imageVersion/u)
    assert.match(run, /node --version/u)
    assert.match(run, /npm --version/u)
    assert.match(run, /git --version/u)
    // 只记录白名单工具链，不启动 Bash，也不 dump 环境。
    assert.doesNotMatch(run, /bash.*--version/iu)
    assert.doesNotMatch(run, /Get-ChildItem Env:|gci Env:|process\.env/iu)
})

test('Windows diagnostics always keep minimal evidence and fail closed without exit-source proof', () => {
    const collect = stepNamed(CI, 'node_daemon', 'Collect Windows process exit diagnostics')
    assert.equal(stepField(collect, 'if'), '${{ always() && runner.os == \'Windows\' }}')
    assert.equal(stepField(collect, 'shell'), 'pwsh')
    assert.equal(stepEnv(collect, 'COVERAGE_OUTCOME'), '${{ steps.coverage.outcome }}')
    const run = stepRun(collect)
    // 事件时间窗与过滤沿用已验证实现，只输出白名单字段。
    assert.match(run, /Get-WinEvent -FilterHashtable @\{ LogName = 'Application'; ProviderName = 'Application Error'; StartTime = \$start \}/u)
    assert.match(run, /\(\?i\)\(node\|bash\|msys\)/u)
    for (const field of ['timestamp', 'AppName', 'ModuleName', 'ExceptionCode', 'FaultingOffset', 'ProcessId']) {
        assert.match(run, new RegExp(`${field} =`, 'u'))
    }
    assert.match(run, /'no-events'/u)
    assert.match(run, /'query-failed'/u)
    assert.match(run, /Application Error query failed/u)
    // 汇总 JSONL 并仅在覆盖率成功时要求 exit-resolved 证据。
    assert.match(run, /ConvertFrom-Json/u)
    assert.match(run, /exit-resolved/u)
    assert.match(run, /collection-summary\.json/u)
    assert.match(run, /coverageOutcome -eq 'success' -and \$exitResolved\.Count -eq 0/u)
    assert.match(run, /coverage did not run in this job/u)
    assert.match(run, /Unparseable diagnostic JSONL/u)

    const upload = stepNamed(CI, 'node_daemon', 'Upload Windows process diagnostic evidence')
    assert.equal(stepField(upload, 'if'), '${{ always() && runner.os == \'Windows\' }}')
    assert.equal(stepField(upload, 'uses'), 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4')
    assert.equal(stepField(upload, 'name', 10), 'windows-process-diagnostics')
    assert.equal(stepField(upload, 'path', 10), 'reports/windows-process-diagnostics/')
    assert.equal(stepField(upload, 'retention-days', 10), '7')
    assert.equal(stepField(upload, 'if-no-files-found', 10), 'error')

    // 上传必须位于采集之后，且两者都不能吞掉缺证据或查询失败。
    const steps = stepsOf(jobBlock(CI, 'node_daemon'))
    assert.ok(steps.indexOf(upload) > steps.indexOf(collect))
    for (const step of [collect, upload]) {
        assert.doesNotMatch(step, /continue-on-error:/u)
    }
})

test('regular daemon CI has no experimental diagnostic jobs, inputs, retries or production entry points', () => {
    // 日常门禁只保留五平台矩阵与原安装器 job；实验矩阵、手工输入和 daemon/diagnostics 不得回流。
    const jobs = [...topLevelSection(CI, 'jobs').matchAll(/^ {2}([A-Za-z0-9_]+):\n/gm)].map((match) => match[1])
    assert.deepEqual(jobs, ['node_daemon', 'windows_daemon_installer'])
    assert.doesNotMatch(CI, /windows_diagnostic_only|windows_experiment|matrix\.sample/u)
    assert.doesNotMatch(CI, /daemon\/diagnostics|windows-native-diagnostic|windows-original-|windows-exact-/u)
    assert.doesNotMatch(CI, /continue-on-error:|repository_dispatch/u)
    assert.doesNotMatch(jobBlock(CI, 'node_daemon'), /retry|--retry|max-parallel/u)
})
