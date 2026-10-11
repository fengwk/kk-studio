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

test('daemon-ci runs the full Node daemon suite on all three platforms', () => {
    // 平台条件用例由各用例自己决定是否执行；矩阵本身必须覆盖三个平台，并且不整体跳过任何一步。
    const job = jobBlock(CI, 'node_daemon')
    const runners = job.match(/^ {8}os: \[(.+)\]$/m)?.[1]?.split(',').map((value) => value.trim())
    assert.deepEqual(runners, ['ubuntu-latest', 'macos-latest', 'windows-latest'])
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
    // 覆盖率步骤是矩阵的最后一道 daemon 门禁，构建必须先于它完成。
    assert.equal(runs.at(-1), 'npm --prefix daemon run coverage')
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

test('the release pipeline smoke-tests the staged Linux archive with its own runtime', () => {
    // 发布前必须用刚暂存的真实归档自证可跑：门禁在 Stage 之后、Publish 之前，且不可跳过。
    const steps = stepsOf(jobBlock(RELEASE, 'publish'))
    const smoke = stepNamed(RELEASE, 'publish', 'Smoke-test the staged Linux archive with its own runtime')
    assert.equal(stepField(smoke, 'if'), undefined, 'the archive smoke must not be conditional')
    assert.doesNotMatch(smoke, /continue-on-error:/u)

    const stageIndex = steps.findIndex((step) => step.includes('scripts/daemon/prepare-release.sh'))
    const smokeIndex = steps.indexOf(smoke)
    const publishIndex = steps.findIndex((step) => stepField(step, 'name') === 'Publish GitHub Release')
    assert.ok(stageIndex >= 0, 'missing the asset staging step')
    assert.ok(stageIndex < smokeIndex && smokeIndex < publishIndex, 'the smoke must run between staging and publishing')

    const run = stepRun(smoke)
    assert.match(run, /scripts\/daemon\/smoke-archive\.sh/u)
    assert.match(run, /linux-amd64/u)
    assert.match(run, /GITHUB_REF_NAME/u)

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
})

test('the release pipeline reuses the same matrix and cannot publish without it', () => {
    const jobs = [...RELEASE.matchAll(/^ {2}([A-Za-z0-9_]+):\n([\s\S]*?)(?=^ {2}\w+:|$(?![\s\S]))/gm)]
        .map(([, name, block]) => ({ name, block }))
    const reused = jobs.find((job) => /^ {4}uses: \.\/\.github\/workflows\/daemon-ci\.yml$/m.test(job.block))
    assert.ok(reused, 'daemon-release must reuse the daemon-ci matrix')

    const needs = jobBlock(RELEASE, 'publish').match(/^ {4}needs:\n((?: {6}- \w+\n)+)/m)?.[1]
    assert.ok(needs, 'missing publish needs')
    for (const required of [reused.name, 'validate_daemon', 'validate_windows_daemon']) {
        assert.match(needs, new RegExp(`^ {6}- ${required}$`, 'm'))
    }
    assert.match(topLevelSection(RELEASE, 'on'), /^ {4}tags:$/m)
})

test('the release version gate reads real project files and fails on any drift', () => {
    const step = stepNamed(RELEASE, 'publish', 'Verify the repository version alignment')
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
