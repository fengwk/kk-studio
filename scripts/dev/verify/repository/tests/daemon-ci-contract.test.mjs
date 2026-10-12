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

function stepNamed(source, job, name) {
    const step = stepsOf(jobBlock(source, job)).find((candidate) => stepField(candidate, 'name') === name)
    assert.ok(step, `missing step: ${name}`)
    return step
}

function dedent(block, width) {
    const prefix = ' '.repeat(width)
    return block.split('\n').map((line) => (line.startsWith(prefix) ? line.slice(width) : line)).join('\n')
}

function pythonHeredoc(script) {
    const lines = script.split('\n')
    const start = lines.findIndex((line) => line.includes("python3 - <<'PY'"))
    assert.ok(start >= 0, 'missing python heredoc')
    const end = lines.indexOf('PY', start + 1)
    assert.ok(end > start, 'unterminated python heredoc')
    return lines.slice(start + 1, end).join('\n')
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
        assert.equal(stepField(step, 'if'), "runner.os == 'macOS'")
    }
})

test('daemon-ci pins the daemon lockfile cache and stays a read-only push/pull-request gate', () => {
    const triggers = topLevelSection(CI, 'on')
    assert.match(triggers, /^ {2}workflow_call:$/m)
    assert.match(triggers, /^ {2}workflow_dispatch:$/m)
    const push = triggers.match(/^ {2}push:\n([\s\S]*?)(?=^ {2}\w)/m)?.[1]
    assert.ok(push, 'missing push trigger')
    assert.deepEqual(
        push.match(/^ {4}branches:\n((?: {6}- .+\n)+)/m)?.[1]?.match(/^ {6}- (.+)$/gm)?.map((line) => line.trim()),
        ['- main', '- dev'],
    )
    assert.match(push, /^ {4}paths:$/m)
    assert.match(triggers, /^ {2}pull_request:$/m)
    assert.match(topLevelSection(CI, 'permissions'), /^ {2}contents: read$/m)
    assert.match(topLevelSection(CI, 'concurrency'), /^ {2}cancel-in-progress: true$/m)

    const setup = stepsOf(jobBlock(CI, 'node_daemon'))
        .find((step) => stepField(step, 'uses')?.startsWith('actions/setup-node@'))
    assert.ok(setup, 'missing setup-node step')
    assert.equal(stepField(setup, 'cache', 10), 'npm')
    assert.equal(stepField(setup, 'cache-dependency-path', 10), 'daemon/package-lock.json')
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
    assert.match(stageNeeds, /^ {6}- validate_daemon$/m)
    assert.match(stageNeeds, /^ {6}- validate_windows_daemon$/m)

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

test('daemon-ci runs the installer and archive smoke on the macOS runner', () => {
    const step = stepNamed(CI, 'node_daemon', 'Test the macOS installer and archive smoke with system Bash')
    assert.equal(stepField(step, 'if'), "runner.os == 'macOS'")
    assert.match(step, /\/bin\/bash --noprofile --norc/u)
    const run = stepRun(step)
    assert.match(run, /python3 -m unittest discover -s scripts\/daemon\/tests/u)
    assert.match(run, /\/bin\/bash --version/u)
})

test('the release pipeline reuses the same matrix and cannot publish without it', () => {
    const jobs = [...RELEASE.matchAll(/^ {2}([A-Za-z0-9_]+):\n([\s\S]*?)(?=^ {2}\w+:|$(?![\s\S]))/gm)]
        .map(([, name, block]) => ({ name, block }))
    const reused = jobs.find((job) => /^ {4}uses: \.\/\.github\/workflows\/daemon-ci\.yml$/m.test(job.block))
    assert.ok(reused, 'daemon-release must reuse the daemon-ci matrix')

    const needs = jobBlock(RELEASE, 'stage').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
    assert.ok(needs, 'missing stage needs')
    for (const required of [reused.name, 'validate_daemon', 'validate_windows_daemon']) {
        assert.match(needs, new RegExp(`^ {6}- ${required}$`, 'm'))
    }
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
    for (const [source, job] of [[DOCKER, 'validate'], [DOCKER, 'validate_unix_daemon'], [RELEASE, 'stage']]) {
        const steps = stepsOf(jobBlock(source, job))
        const install = steps.findIndex((step) => stepField(step, 'run') === 'npm --prefix daemon ci')
        const scriptTests = steps.findIndex((step) => stepField(step, 'name') === (
            job === 'validate_unix_daemon' ? 'Test installer with system Bash' : 'Run repository script tests'
        ))
        assert.ok(install >= 0 && scriptTests > install, `${job} must install the real tar parser before fixtures`)
    }
})

test('the release version gate reads real project files and fails on any drift', () => {
    const step = stepNamed(RELEASE, 'stage', 'Verify the repository version alignment')
    assert.doesNotMatch(stepField(step, 'name'), /\d+\.\d+\.\d+/u, 'the gate must not pin a version in its name')
    const script = dedent(stepRun(step), 10)
    const python = pythonHeredoc(script)
    // 版本来自真实文件：pom.xml 的元素与属性、daemon/package.json 与 tag；不写死任何版本字面量。
    assert.match(python, /xml\.etree\.ElementTree/u)
    assert.match(python, /daemon\/package\.json/u)
    assert.match(python, /GITHUB_REF_NAME/u)
    assert.doesNotMatch(python, /["']\d+\.\d+\.\d+["']/u, 'the gate must not hardcode a version')

    const runGate = (fixturePath, tag) => spawnSync('bash', ['-s'], {
        input: `${script}\n`,
        cwd: fixturePath,
        env: { ...process.env, GITHUB_REF_NAME: tag },
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
        assert.equal(version.stdout, 'kk-studio-daemon 1.0.13\n')
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
