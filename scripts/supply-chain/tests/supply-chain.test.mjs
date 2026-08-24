import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { mkdtempSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/supply-chain.sh')
const POM = path.join(REPOSITORY_ROOT, 'pom.xml')
const WEB_POM = path.join(REPOSITORY_ROOT, 'web/pom.xml')
const SUPPRESSIONS = path.join(
    REPOSITORY_ROOT,
    'config/supply-chain/dependency-check-suppressions.xml',
)
const NVD_DATAFEED_ARGUMENT =
    '-DnvdDatafeedUrl=https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz'

function createFakeToolchain() {
    const root = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-supply-chain-test-'))
    const bin = path.join(root, 'bin')
    const javaHome = path.join(root, 'jdk')
    mkdirSync(bin, { recursive: true })
    mkdirSync(path.join(javaHome, 'bin'), { recursive: true })

    writeFileSync(
        path.join(javaHome, 'bin/java'),
        '#!/usr/bin/env bash\nexit 0\n',
    )
    chmodSync(path.join(javaHome, 'bin/java'), 0o755)

    writeFileSync(
        path.join(bin, 'mvn'),
        `#!/usr/bin/env bash
set -euo pipefail
if [[ -n "\${FAKE_MVN_ARGS_FILE:-}" ]]; then
    printf '%s\\n' "$@" >"$FAKE_MVN_ARGS_FILE"
fi
report_dir=
for arg in "$@"; do
    case "$arg" in
        -Dsupply-chain.report.directory=*) report_dir="\${arg#*=}" ;;
    esac
done
[[ -n "$report_dir" ]]
mkdir -p "$report_dir"
if [[ " $* " == *" -Ddependency-check.skip=true "* ]]; then
    printf '%s\\n' '{"bom-ref":"fake-backend"}' >"$report_dir/bom.json"
else
    printf '%s\\n' '<html>fake dependency-check report</html>' >"$report_dir/dependency-check-report.html"
    printf '%s\\n' '{"dependencies":[]}' >"$report_dir/dependency-check-report.json"
    printf '%s\\n' '{"version":"2.1.0","runs":[]}' >"$report_dir/dependency-check-report.sarif"
fi
`,
    )
    chmodSync(path.join(bin, 'mvn'), 0o755)

    writeFileSync(
        path.join(bin, 'npm'),
        `#!/usr/bin/env bash
set -euo pipefail
case " $* " in
    *" sbom "*) printf '%s\\n' '{"bomFormat":"CycloneDX","components":[{"name":"fake-frontend"}]}' ;;
    *" audit "*) printf '%s\\n' '{"auditReportVersion":2,"vulnerabilities":{}}' ;;
    *) exit 3 ;;
esac
`,
    )
    chmodSync(path.join(bin, 'npm'), 0o755)

    return {
        root,
        javaHome,
        env: {
            ...process.env,
            PATH: `${bin}${path.delimiter}${process.env.PATH ?? ''}`,
            JAVA_HOME_21: javaHome,
            JAVA_HOME: javaHome,
        },
    }
}

function runScript(args, environment = {}) {
    return spawnSync('bash', [SCRIPT, ...args], {
        cwd: REPOSITORY_ROOT,
        env: { ...environment },
        encoding: 'utf8',
    })
}

function listRunDirectories(reportRoot) {
    return readdirSync(reportRoot, { withFileTypes: true })
        .filter(entry => entry.isDirectory() && entry.name !== 'latest' && !entry.name.startsWith('.'))
        .map(entry => entry.name)
}

test('rejects missing, unknown, and extra command parameters', () => {
    // Intent: malformed invocations must stop before creating an online scan or report.
    for (const args of [[], ['unknown'], ['sbom', 'extra']]) {
        const result = runScript(args)
        assert.equal(result.status, 2, `${args.join(' ')}: ${result.stderr}`)
    }
})

test('keeps online plugins inside the explicitly activated root-only profile', () => {
    // Intent: ordinary `mvn verify` must not resolve or execute online vulnerability plugins.
    const pom = readFileSync(POM, 'utf8')
    const profileStart = pom.indexOf('<profiles>')
    const profileEnd = pom.indexOf('</profiles>')
    assert.notEqual(profileStart, -1)
    assert.notEqual(profileEnd, -1)
    assert.doesNotMatch(pom.slice(0, profileStart), /cyclonedx-maven-plugin|dependency-check-maven/)

    const profile = pom.slice(profileStart, profileEnd)
    assert.match(profile, /<id>supply-chain<\/id>/)
    assert.match(profile, /<inherited>false<\/inherited>/g)
    assert.match(profile, /<phase>package<\/phase>[\s\S]*<goal>makeAggregateBom<\/goal>/)
    assert.match(profile, /<phase>verify<\/phase>[\s\S]*<goal>aggregate<\/goal>/)
})

test('locks tool versions, policy thresholds, and secret indirection', () => {
    // Intent: policy changes must be reviewable as a small static diff and keys must stay out of POM/CLI text.
    const pom = readFileSync(POM, 'utf8')
    const webPom = readFileSync(WEB_POM, 'utf8')
    const script = readFileSync(SCRIPT, 'utf8')
    const suppressions = readFileSync(SUPPRESSIONS, 'utf8')
    assert.match(pom, /<spring-boot\.version>3\.5\.16<\/spring-boot\.version>/)
    assert.match(pom, /<jackson\.version>2\.22\.1<\/jackson\.version>/)
    assert.match(pom, /<netty\.version>4\.1\.137\.Final<\/netty\.version>/)
    assert.match(pom, /<log4j\.version>2\.26\.1<\/log4j\.version>/)
    assert.match(pom, /<tomcat\.version>10\.1\.59<\/tomcat\.version>/)
    assert.match(pom, /<postgresql\.version>42\.7\.13<\/postgresql\.version>/)
    assert.match(pom, /<opennlp\.version>2\.5\.11<\/opennlp\.version>/)
    assert.match(
        pom,
        /<artifactId>spring-boot-dependencies<\/artifactId>[\s\S]*<version>\$\{spring-boot\.version\}<\/version>[\s\S]*<scope>import<\/scope>/,
    )
    assert.match(
        pom,
        /<artifactId>jackson-bom<\/artifactId>[\s\S]*<version>\$\{jackson\.version\}<\/version>[\s\S]*<scope>import<\/scope>/,
    )
    assert.match(
        pom,
        /<artifactId>netty-bom<\/artifactId>[\s\S]*<version>\$\{netty\.version\}<\/version>[\s\S]*<scope>import<\/scope>/,
    )
    assert.match(
        pom,
        /<artifactId>log4j-bom<\/artifactId>[\s\S]*<version>\$\{log4j\.version\}<\/version>[\s\S]*<scope>import<\/scope>/,
    )
    assert.match(
        webPom,
        /<artifactId>spring-boot-maven-plugin<\/artifactId>[\s\S]*<version>\$\{spring-boot\.version\}<\/version>/,
    )
    assert.doesNotMatch(webPom, /<version>3\.5\.0<\/version>/)
    assert.match(pom, /<artifactId>cyclonedx-maven-plugin<\/artifactId>[\s\S]*<version>2\.9\.3<\/version>/)
    assert.match(pom, /<artifactId>dependency-check-maven<\/artifactId>[\s\S]*<version>13\.0\.0<\/version>/)
    assert.match(pom, /<schemaVersion>1\.6<\/schemaVersion>/)
    assert.match(pom, /<includeTestScope>false<\/includeTestScope>/)
    assert.match(pom, /<format>HTML<\/format>[\s\S]*<format>JSON<\/format>[\s\S]*<format>SARIF<\/format>/)
    assert.match(pom, /<failBuildOnCVSS>7<\/failBuildOnCVSS>/)
    assert.match(pom, /<ossIndexAnalyzerEnabled>false<\/ossIndexAnalyzerEnabled>/)
    assert.doesNotMatch(pom, /nvdApiServerId|<nvdApiKey>|NVD_API_KEY/)
    const suppressionBlocks = [...suppressions.matchAll(/<suppress>([\s\S]*?)<\/suppress>/g)].map(
        match => match[1],
    )
    assert.equal(suppressionBlocks.length, 7)
    for (const block of suppressionBlocks) {
        assert.match(block, /<notes>[\s\S]+<\/notes>/)
        assert.match(block, /<cve>CVE-\d{4}-\d+<\/cve>/)
        assert.match(block, /<gav>[^<*?]+:[^<*?]+:[^<*?]+<\/gav>/)
        assert.doesNotMatch(block, /<cpe>|<cvss>|regex\s*=/)
    }
    assert.match(script, /chmod 600/)
    assert.match(script, /nvdApiServerId/)
    assert.match(script, /nvdDatafeedUrl/)
    assert.match(script, /nvd\.nist\.gov\/feeds\/json\/cve\/2\.0\/nvdcve-2\.0-\{0\}\.json\.gz/)
    assert.doesNotMatch(script, /-DnvdApiKey(?:=|\s)/)
    assert.doesNotMatch(script, /echo\s+["']?\$NVD_API_KEY/)
})

test('honors a custom report root and publishes timestamped and latest reports', () => {
    // Intent: callers must be able to isolate reports while retaining the repository's latest-report workflow.
    const toolchain = createFakeToolchain()
    const reportRoot = path.join(toolchain.root, 'custom-reports')
    try {
        const result = runScript(['sbom'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
        })
        assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)

        const runs = listRunDirectories(reportRoot)
        assert.equal(runs.length, 1)
        assert.match(runs[0], /^\d{8}T\d{6}Z-\d+$/)
        assert.equal(readFileSync(path.join(reportRoot, 'LATEST_RUN.txt'), 'utf8').trim(), path.join(reportRoot, runs[0]))

        const summary = JSON.parse(readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'))
        assert.equal(summary.status, 'PASS')
        assert.equal(summary.mode, 'sbom')
        assert.equal(summary.checks.find(check => check.name === 'backend-sbom').status, 'PASS')
        assert.equal(summary.checks.find(check => check.name === 'frontend-sbom').status, 'PASS')
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('uses the official NVD feed without a server id when no key is set', () => {
    // Intent: the no-key path must not inherit a missing Maven server credential.
    const toolchain = createFakeToolchain()
    const reportRoot = path.join(toolchain.root, 'feed-reports')
    const argsFile = path.join(toolchain.root, 'maven-args.txt')
    try {
        const result = runScript(['audit'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            FAKE_MVN_ARGS_FILE: argsFile,
        })
        assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
        const args = readFileSync(argsFile, 'utf8').split('\n').filter(Boolean)
        assert.equal(args.includes(NVD_DATAFEED_ARGUMENT), true)
        assert.equal(args.some(arg => arg.includes('nvdApiServerId')), false)
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('does not expose an NVD key while using the protected settings path', () => {
    // Intent: a provided key may authenticate the scan, but it must not enter CLI arguments, logs, or reports.
    const toolchain = createFakeToolchain()
    const reportRoot = path.join(toolchain.root, 'key-reports')
    const argsFile = path.join(toolchain.root, 'maven-args.txt')
    const secret = 'test-nvd-key-must-not-leak'
    try {
        const result = runScript(['audit'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            NVD_API_KEY: secret,
            FAKE_MVN_ARGS_FILE: argsFile,
        })
        assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
        assert.equal(result.stdout.includes(secret), false)
        assert.equal(result.stderr.includes(secret), false)
        const args = readFileSync(argsFile, 'utf8').split('\n').filter(Boolean)
        assert.equal(args.includes('-DnvdApiServerId=kk-studio-supply-chain-nvd'), true)
        assert.equal(args.includes('-s'), true)
        assert.equal(args.some(arg => arg.includes(secret)), false)

        const files = []
        const visit = directory => {
            for (const entry of readdirSync(directory, { withFileTypes: true })) {
                const target = path.join(directory, entry.name)
                if (entry.isDirectory()) {
                    visit(target)
                } else {
                    files.push(target)
                }
            }
        }
        visit(reportRoot)
        assert.equal(files.some(file => readFileSync(file, 'utf8').includes(secret)), false)
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})
