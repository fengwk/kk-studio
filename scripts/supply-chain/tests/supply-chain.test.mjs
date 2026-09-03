import assert from 'node:assert/strict'
import {
    chmodSync,
    existsSync,
    mkdirSync,
    readFileSync,
    readdirSync,
    rmSync,
    writeFileSync,
} from 'node:fs'
import { mkdtempSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/supply-chain.sh')
const POM = path.join(REPOSITORY_ROOT, 'pom.xml')
const CANVAS_INFRA_POM = path.join(REPOSITORY_ROOT, 'canvas/infra/pom.xml')
const PLATFORM_POM = path.join(REPOSITORY_ROOT, 'platform/pom.xml')
const WEB_POM = path.join(REPOSITORY_ROOT, 'web/pom.xml')
const SUPPRESSIONS = path.join(
    REPOSITORY_ROOT,
    'config/supply-chain/dependency-check-suppressions.xml',
)
const NVD_DATAFEED_ARGUMENT =
    '-DnvdDatafeedUrl=https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz'
const NPM_AUDIT_REGISTRY = 'https://registry.npmjs.org'

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
    if [[ "\${FAKE_MVN_REPORT_VULNERABILITY:-}" == true ]]; then
        printf '%s\\n' '{"dependencies":[{"fileName":"fake-vulnerable.jar","packages":[{"id":"pkg:maven/example:fake-vulnerable@1.0.0"}],"vulnerabilities":[{"name":"CVE-2099-0001","cvssv3":{"baseScore":5.3}}]}]}' >"$report_dir/dependency-check-report.json"
    else
        printf '%s\\n' '{"dependencies":[]}' >"$report_dir/dependency-check-report.json"
    fi
    printf '%s\\n' '{"version":"2.1.0","runs":[]}' >"$report_dir/dependency-check-report.sarif"
fi
`,
    )
    chmodSync(path.join(bin, 'mvn'), 0o755)

    writeFileSync(
        path.join(bin, 'npm'),
        `#!/usr/bin/env bash
set -euo pipefail
if [[ -n "\${FAKE_NPM_ARGS_FILE:-}" ]]; then
    printf '%s\\n' "$@" >>"$FAKE_NPM_ARGS_FILE"
fi
case " $* " in
    *" sbom "*)
        printf '%s\\n' '{"bomFormat":"CycloneDX","components":[{"name":"fake-frontend"}]}'
        ;;
    *" audit "*)
        registry=
        for arg in "$@"; do
            case "$arg" in
                --registry=*) registry="\${arg#*=}" ;;
            esac
        done
        configured_mirror="\${npm_config_registry:-\${NPM_CONFIG_REGISTRY:-}}"
        effective_registry="\${registry:-\$configured_mirror}"
        if [[ -n "$configured_mirror" && "$effective_registry" != "https://registry.npmjs.org" && "$effective_registry" != "https://registry.npmjs.org/" ]]; then
            printf '%s\\n' 'npm error audit 404 Not Found - POST https://registry.npmmirror.com/-/npm/v1/security/advisories/bulk - [NOT_IMPLEMENTED]' >&2
            exit 1
        fi
        if [[ "\${FAKE_NPM_REPORT_VULNERABILITY:-}" == true ]]; then
            printf '%s\\n' '{"auditReportVersion":2,"vulnerabilities":{"browserslist":{"name":"browserslist","severity":"high"}}}'
            exit 1
        fi
        printf '%s\\n' '{"auditReportVersion":2,"vulnerabilities":{}}'
        ;;
    *)
        exit 3
        ;;
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

function createFakeDockerToolchain() {
    const root = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-supply-chain-docker-test-'))
    const bin = path.join(root, 'bin')
    const callsFile = path.join(root, 'docker-calls.txt')
    mkdirSync(bin, { recursive: true })

    writeFileSync(
        path.join(bin, 'docker'),
        `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >>"\${FAKE_DOCKER_CALLS_FILE}"

case "\${1:-}" in
    build)
        exit 0
        ;;
    image)
        if [[ "\${2:-}" != inspect ]]; then
            exit 2
        fi
        format=
        previous=
        for arg in "$@"; do
            if [[ "$previous" == --format ]]; then
                format="$arg"
            fi
            previous="$arg"
        done
        image="\${@: -1}"
        if [[ "$format" == "{{.Id}}" ]]; then
            if [[ "$image" == *daemon* ]]; then
                printf '%s\\n' 'sha256:2222222222222222222222222222222222222222222222222222222222222222'
            else
                printf '%s\\n' 'sha256:1111111111111111111111111111111111111111111111111111111111111111'
            fi
        elif [[ "$image" == *daemon* ]]; then
            printf '%s\\n' 'kk-studio-daemon@sha256:4444444444444444444444444444444444444444444444444444444444444444'
        else
            printf '%s\\n' 'kk-studio-app@sha256:3333333333333333333333333333333333333333333333333333333333333333'
        fi
        exit 0
        ;;
    run)
        version=false
        smoke=false
        for arg in "$@"; do
            if [[ "$arg" == version ]]; then
                version=true
            fi
            if [[ "$arg" == /bin/sh || "$arg" == /bin/bash ]]; then
                smoke=true
            fi
        done
        if [[ "$version" == true ]]; then
            printf '%s\\n' '{"Version":"0.74.0"}'
        elif [[ "$smoke" == true ]]; then
            smoke_failure="\${FAKE_DOCKER_SMOKE_FAILURE:-}"
            if [[ "$smoke_failure" == true || "$smoke_failure" == all ]] || \
                [[ -n "$smoke_failure" && "$*" == *"$smoke_failure"* ]]; then
                printf '%s\\n' 'simulated image smoke failure' >&2
                exit 17
            fi
            if [[ -n "\${HTTP_PROXY:-}" ]]; then
                printf 'proxy=%s\\n' "\$HTTP_PROXY"
            fi
            printf '%s\\n' \
                'uid=10001 user=kkdaemon' \
                'v22.19.0' \
                '11.19.0' \
                '/usr/bin/bash' \
                '/usr/bin/git' \
                'package-lock.json contains lodash@4.17.21'
        elif [[ "\${FAKE_DOCKER_REPORT_VULNERABILITY:-false}" == true ]]; then
            printf '%s\\n' '{"SchemaVersion":2,"ArtifactName":"fake-image","Results":[{"Target":"ubuntu","Vulnerabilities":[{"VulnerabilityID":"CVE-2099-0002","PkgName":"fake-package","Severity":"HIGH","FixedVersion":"1.2.3"}]}]}'
        else
            printf '%s\\n' '{"SchemaVersion":2,"ArtifactName":"fake-image","Results":[]}'
        fi
        exit 0
        ;;
    *)
        exit 2
        ;;
esac
`,
    )
    chmodSync(path.join(bin, 'docker'), 0o755)

    return {
        root,
        callsFile,
        env: {
            ...process.env,
            PATH: `${bin}${path.delimiter}${process.env.PATH ?? ''}`,
            FAKE_DOCKER_CALLS_FILE: callsFile,
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
    const canvasInfraPom = readFileSync(CANVAS_INFRA_POM, 'utf8')
    const platformPom = readFileSync(PLATFORM_POM, 'utf8')
    const webPom = readFileSync(WEB_POM, 'utf8')
    const script = readFileSync(SCRIPT, 'utf8')
    assert.match(pom, /<spring-boot\.version>4\.0\.8<\/spring-boot\.version>/)
    assert.match(pom, /<jackson\.version>2\.22\.1<\/jackson\.version>/)
    assert.match(pom, /<netty\.version>4\.1\.137\.Final<\/netty\.version>/)
    assert.match(pom, /<log4j\.version>2\.26\.1<\/log4j\.version>/)
    assert.match(pom, /<tomcat\.version>10\.1\.59<\/tomcat\.version>/)
    assert.match(pom, /<postgresql\.version>42\.7\.13<\/postgresql\.version>/)
    assert.match(pom, /<opennlp\.version>2\.5\.11<\/opennlp\.version>/)
    assert.doesNotMatch(pom, /<kotlin\.version>|kotlin-bom|okio-jvm/)
    assert.doesNotMatch(platformPom, /org\.jetbrains\.kotlin|kotlin-stdlib-common/)
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
    assert.match(pom, /<failBuildOnCVSS>0<\/failBuildOnCVSS>/)
    assert.match(pom, /<ossIndexAnalyzerEnabled>false<\/ossIndexAnalyzerEnabled>/)
    assert.match(script, /NPM_AUDIT_REGISTRY=https:\/\/registry\.npmjs\.org/)
    assert.match(script, /audit[\s\S]*--audit-level=low/)
    assert.match(script, /audit[\s\S]*--registry="?\$NPM_AUDIT_REGISTRY"?/)
    assert.match(script, /sbom[\s\S]*--package-lock-only/)
    assert.match(script, /all\)\s*\n\s*run_sbom\s*\n\s*run_audit\s*\n\s*run_image/)
    assert.doesNotMatch(pom, /nvdApiServerId|<nvdApiKey>|NVD_API_KEY/)

    // Zero suppressions and strict build hygiene policy assertions
    assert.equal(existsSync(SUPPRESSIONS), false)
    assert.doesNotMatch(pom, /<suppressionFile>|suppressionFile/)
    assert.doesNotMatch(pom, /<parent>/)
    assert.doesNotMatch(pom, /convention4j-parent/)
    assert.doesNotMatch(pom, /auto-mapper-processor|mapstruct-processor|auto-service/)
    assert.doesNotMatch(canvasInfraPom, /spring-boot-starter-aop/)
    assert.doesNotMatch(platformPom, /spring-boot-starter-aop/)
    assert.doesNotMatch(webPom, /spring-boot-starter-aop/)
    assert.match(
        pom,
        /<exclusion>[\s\S]*?<artifactId>spring-boot-starter-aop<\/artifactId>[\s\S]*?<\/exclusion>/,
    )
    assert.match(canvasInfraPom, /<artifactId>spring-boot-starter-aspectj<\/artifactId>/)
    assert.match(platformPom, /<artifactId>spring-boot-starter-aspectj<\/artifactId>/)
    assert.match(pom, /<convention4j\.version>1\.2\.2<\/convention4j\.version>/)
    assert.match(pom, /<artifactId>convention4j-spring-boot-starter<\/artifactId>/)
    assert.match(pom, /<artifactId>convention4j-spring-boot-starter-web<\/artifactId>/)
    assert.match(pom, /<artifactId>convention4j-spring-boot-starter-test<\/artifactId>/)
    assert.match(pom, /<artifactId>convention4j-comfyui<\/artifactId>/)
    assert.match(
        pom,
        /<artifactId>mybatis-spring-boot-starter<\/artifactId>[\s\S]*?<version>\$\{mybatis-spring-boot-starter\.version\}<\/version>/,
    )
    assert.match(
        pom,
        /<artifactId>lombok<\/artifactId>[\s\S]*?<scope>provided<\/scope>/,
    )
    assert.match(
        pom,
        /<artifactId>maven-compiler-plugin<\/artifactId>[\s\S]*?<version>3\.14\.0<\/version>[\s\S]*?<compilerArgs>[\s\S]*?<arg>-parameters<\/arg>/,
    )
    assert.match(
        pom,
        /<artifactId>maven-surefire-plugin<\/artifactId>[\s\S]*?<version>3\.5\.3<\/version>/,
    )
    assert.match(
        pom,
        /<artifactId>jacoco-maven-plugin<\/artifactId>[\s\S]*?<version>0\.8\.11<\/version>/,
    )
    assert.match(pom, /<goal>prepare-agent<\/goal>/)
    assert.match(pom, /<goal>report<\/goal>/)
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
        const summaryMarkdown = readFileSync(path.join(reportRoot, 'latest/summary.md'), 'utf8')
        assert.match(summaryMarkdown, /requested backend and frontend SBOM checks completed/i)
        assert.doesNotMatch(summaryMarkdown, /Dependency-Check JSON contains zero/)
        assert.doesNotMatch(summaryMarkdown, /image functional smokes passed/)
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

test('pins npm audit to the official registry and ignores configured developer mirrors', () => {
    // Intent: developer or CI mirror configuration must not redirect the security audit to unsupported endpoints.
    const toolchain = createFakeToolchain()
    const reportRoot = path.join(toolchain.root, 'npm-mirror-reports')
    const npmArgsFile = path.join(toolchain.root, 'npm-args.txt')
    try {
        const result = runScript(['audit'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            FAKE_NPM_ARGS_FILE: npmArgsFile,
            npm_config_registry: 'https://registry.npmmirror.com/',
            NPM_CONFIG_REGISTRY: 'https://registry.npmmirror.com/',
        })
        assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
        const args = readFileSync(npmArgsFile, 'utf8').split('\n').filter(Boolean)
        assert.equal(args.includes('audit'), true)
        assert.equal(args.includes(`--registry=${NPM_AUDIT_REGISTRY}`), true)
        assert.equal(args.includes('--audit-level=low'), true)
        assert.equal(args.includes('--json'), true)
        assert.equal(args.some(arg => arg.startsWith('--omit')), false)

        const summary = JSON.parse(
            readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'),
        )
        assert.equal(summary.status, 'PASS')
        assert.equal(summary.checks.find(check => check.name === 'npm-audit').status, 'PASS')
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('fails closed when npm audit reports a vulnerability', () => {
    // Intent: frontend audit findings must fail closed and record a failure in summary.
    const toolchain = createFakeToolchain()
    const reportRoot = path.join(toolchain.root, 'npm-vulnerability-reports')
    try {
        const result = runScript(['audit'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            FAKE_NPM_REPORT_VULNERABILITY: 'true',
        })
        assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`)
        const summary = JSON.parse(
            readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'),
        )
        assert.equal(summary.status, 'FAIL')
        assert.equal(summary.checks.find(check => check.name === 'npm-audit').status, 'FAIL')
        assert.match(summary.failures.join('\n'), /npm audit found vulnerabilities/)
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('fails closed when a successful Maven scan reports a vulnerability', () => {
    // Intent: a zero Maven exit code must not turn a report containing findings into PASS.
    const toolchain = createFakeToolchain()
    const reportRoot = path.join(toolchain.root, 'vulnerability-reports')
    try {
        const result = runScript(['audit'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            FAKE_MVN_REPORT_VULNERABILITY: 'true',
        })
        assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`)
        const summary = JSON.parse(
            readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'),
        )
        assert.equal(summary.status, 'FAIL')
        assert.equal(
            summary.checks.find(check => check.name === 'maven-dependency-check').status,
            'FAIL',
        )
        assert.match(summary.failures.join('\n'), /non-suppressed vulnerabilities/)
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
test('builds both current images and constructs a pinned proxy-aware Trivy scan', () => {
    // Intent: the image gate must build source images, pin the scanner and keep loopback proxy values out of scanner argv.
    const toolchain = createFakeDockerToolchain()
    const reportRoot = path.join(toolchain.root, 'image-reports')
    const appImage = 'example/kk-studio-app:test'
    const daemonImage = 'example/kk-studio-daemon:test'
    try {
        const result = runScript(['image'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            SUPPLY_CHAIN_APP_IMAGE: appImage,
            SUPPLY_CHAIN_DAEMON_IMAGE: daemonImage,
            HTTP_PROXY: 'http://127.0.0.1:7890',
            HTTPS_PROXY: '',
            ALL_PROXY: '',
            NO_PROXY: 'localhost,127.0.0.1',
            http_proxy: '',
            https_proxy: '',
            all_proxy: '',
            no_proxy: '',
        })
        assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
        assert.doesNotMatch(result.stdout, /127\.0\.0\.1:7890/)
        assert.doesNotMatch(result.stderr, /127\.0\.0\.1:7890/)
        const summaryMarkdown = readFileSync(path.join(reportRoot, 'latest/summary.md'), 'utf8')
        assert.match(summaryMarkdown, /requested image checks completed/i)
        assert.doesNotMatch(summaryMarkdown, /Dependency-Check JSON contains zero/)

        const calls = readFileSync(toolchain.callsFile, 'utf8')
        assert.match(calls, /build .*deploy\/local\/Dockerfile/)
        assert.match(calls, /build .*deploy\/reliability\/daemon\.Dockerfile/)
        assert.match(calls, new RegExp(`--tag ${appImage}`))
        assert.match(calls, new RegExp(`--tag ${daemonImage}`))
        assert.doesNotMatch(calls, /docker push| push /)
        assert.match(calls, /--entrypoint \/bin\/sh/)
        assert.match(calls, /--entrypoint \/bin\/bash/)
        assert.match(calls, /test "\$uid" -ne 0/)
        assert.match(calls, /java -version/)
        assert.match(calls, /node --version/)
        assert.match(calls, /npm --version/)
        assert.match(calls, /command -v ffmpeg/)
        assert.match(calls, /command -v ffprobe/)
        assert.match(calls, /command -v curl/)
        assert.match(calls, /command -v bash/)
        assert.match(calls, /command -v git/)
        assert.match(
            calls,
            /npm install --package-lock-only --ignore-scripts --no-audit --no-fund lodash@4\.17\.21/,
        )
        assert.equal(
            calls.indexOf('--entrypoint /bin/bash') <
                calls.indexOf('aquasec/trivy@sha256:'),
            true,
        )

        const runCalls = calls
            .split('\n')
            .filter(line => line.startsWith('run '))
            .join('\n')
        assert.match(
            runCalls,
            /aquasec\/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969/,
        )
        assert.match(runCalls, /public\.ecr\.aws\/aquasecurity\/trivy-db:2/)
        assert.match(runCalls, /public\.ecr\.aws\/aquasecurity\/trivy-java-db:1/)
        assert.match(runCalls, /--volume \/var\/run\/docker\.sock:\/var\/run\/docker\.sock/)
        assert.match(runCalls, /--volume kk-studio-trivy-cache:\/root\/\.cache\/trivy/)
        assert.match(runCalls, /--network host/)
        assert.match(runCalls, /--env HTTP_PROXY/)
        assert.doesNotMatch(runCalls, /127\.0\.0\.1:7890/)
        assert.match(runCalls, /--scanners vuln/)
        assert.match(runCalls, /--ignore-unfixed/)

        const summary = JSON.parse(
            readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'),
        )
        assert.equal(summary.status, 'PASS')
        assert.equal(summary.checks.find(check => check.name === 'app-image-smoke').status, 'PASS')
        assert.equal(
            summary.checks.find(check => check.name === 'daemon-image-smoke').status,
            'PASS',
        )
        assert.equal(summary.checks.find(check => check.name === 'app-image-scan').status, 'PASS')
        assert.equal(
            summary.checks.find(check => check.name === 'daemon-image-scan').status,
            'PASS',
        )
        const appScan = summary.checks.find(check => check.name === 'app-image-scan')
        assert.equal(appScan.image, appImage)
        assert.match(appScan.imageId, /^sha256:/)
        assert.match(appScan.imageDigest, /@sha256:/)
        assert.equal(appScan.scannerVersion, '0.74.0')
        assert.equal(appScan.scannerVersionStatus, 'PASS')
        assert.equal(existsSync(path.join(reportRoot, 'latest/image/app.json')), true)
        assert.equal(existsSync(path.join(reportRoot, 'latest/image/daemon.json')), true)
        assert.equal(
            existsSync(path.join(reportRoot, 'latest/logs/app-image-smoke.log')),
            true,
        )
        assert.equal(
            existsSync(path.join(reportRoot, 'latest/logs/daemon-image-smoke.log')),
            true,
        )
        for (const smokeLog of ['app-image-smoke.log', 'daemon-image-smoke.log']) {
            const content = readFileSync(path.join(reportRoot, 'latest/logs', smokeLog), 'utf8')
            assert.doesNotMatch(content, /127\.0\.0\.1:7890/)
            assert.match(content, /\[redacted-proxy\]/)
        }
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('fails closed when the daemon smoke fails even though Trivy exits zero', () => {
    // Intent: runtime contract failures must block the image gate independently of a clean vulnerability report.
    const toolchain = createFakeDockerToolchain()
    const reportRoot = path.join(toolchain.root, 'smoke-failure-reports')
    try {
        const result = runScript(['image'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            FAKE_DOCKER_SMOKE_FAILURE: 'daemon',
            HTTP_PROXY: '',
            HTTPS_PROXY: '',
            ALL_PROXY: '',
            NO_PROXY: '',
            http_proxy: '',
            https_proxy: '',
            all_proxy: '',
            no_proxy: '',
        })
        assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`)

        const summary = JSON.parse(
            readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'),
        )
        assert.equal(summary.status, 'FAIL')
        assert.equal(summary.checks.find(check => check.name === 'app-image-smoke').status, 'PASS')
        assert.equal(
            summary.checks.find(check => check.name === 'daemon-image-smoke').status,
            'FAIL',
        )
        assert.equal(summary.checks.find(check => check.name === 'app-image-scan').status, 'PASS')
        assert.equal(
            summary.checks.find(check => check.name === 'daemon-image-scan').status,
            'PASS',
        )
        assert.match(summary.failures.join('\n'), /daemon image smoke failed/)
        assert.match(
            readFileSync(path.join(reportRoot, 'latest/logs/daemon-image-smoke.log'), 'utf8'),
            /simulated image smoke failure/,
        )
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('uses the named Trivy cache in offline mode when requested', () => {
    // Intent: an existing cache must be usable without silently attempting a database update or network access.
    const toolchain = createFakeDockerToolchain()
    const reportRoot = path.join(toolchain.root, 'offline-reports')
    try {
        const result = runScript(['image'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            TRIVY_SKIP_DB_UPDATE: 'true',
            HTTP_PROXY: '',
            HTTPS_PROXY: '',
            ALL_PROXY: '',
            NO_PROXY: '',
            http_proxy: '',
            https_proxy: '',
            all_proxy: '',
            no_proxy: '',
        })
        assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)

        const runCalls = readFileSync(toolchain.callsFile, 'utf8')
            .split('\n')
            .filter(line => line.startsWith('run '))
            .join('\n')
        assert.match(runCalls, /--volume kk-studio-trivy-cache:\/root\/\.cache\/trivy/)
        assert.match(runCalls, /--skip-db-update --skip-java-db-update --offline-scan/)
        assert.doesNotMatch(runCalls, /--network host/)
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('fails closed when Trivy exits zero but its JSON has a fixable vulnerability', () => {
    // Intent: the report parser is a second independent gate and must reject findings even when the tool exit code is zero.
    const toolchain = createFakeDockerToolchain()
    const reportRoot = path.join(toolchain.root, 'vulnerability-image-reports')
    try {
        const result = runScript(['image'], {
            ...toolchain.env,
            SUPPLY_CHAIN_REPORT_ROOT: reportRoot,
            FAKE_DOCKER_REPORT_VULNERABILITY: 'true',
            HTTP_PROXY: '',
            HTTPS_PROXY: '',
            ALL_PROXY: '',
            NO_PROXY: '',
            http_proxy: '',
            https_proxy: '',
            all_proxy: '',
            no_proxy: '',
        })
        assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`)

        const summary = JSON.parse(
            readFileSync(path.join(reportRoot, 'latest/summary.json'), 'utf8'),
        )
        assert.equal(summary.status, 'FAIL')
        assert.equal(
            summary.checks.find(check => check.name === 'app-image-scan').status,
            'FAIL',
        )
        assert.match(summary.failures.join('\n'), /HIGH\/CRITICAL vulnerabilities/)
        assert.equal(existsSync(path.join(reportRoot, 'latest/image/app.json')), true)
        assert.equal(existsSync(path.join(reportRoot, 'latest/image/daemon.json')), true)
    } finally {
        rmSync(toolchain.root, { recursive: true, force: true })
    }
})

test('keeps runtime apt upgrade before install in both image Dockerfiles', () => {
    // Intent: every runtime build must absorb Ubuntu security updates before adding runtime packages.
    for (const dockerfile of [
        path.join(REPOSITORY_ROOT, 'deploy/local/Dockerfile'),
        path.join(REPOSITORY_ROOT, 'deploy/reliability/daemon.Dockerfile'),
    ]) {
        const source = readFileSync(dockerfile, 'utf8')
        const runtime = source.slice(source.lastIndexOf('\nFROM '))
        const updateIndex = runtime.indexOf('apt-get update')
        const upgradeIndex = runtime.indexOf('DEBIAN_FRONTEND=noninteractive apt-get upgrade --yes')
        const installIndex = runtime.indexOf('apt-get install')
        assert.notEqual(updateIndex, -1, dockerfile)
        assert.notEqual(upgradeIndex, -1, dockerfile)
        assert.notEqual(installIndex, -1, dockerfile)
        assert.equal(upgradeIndex > updateIndex, true, dockerfile)
        assert.equal(upgradeIndex < installIndex, true, dockerfile)
        assert.match(runtime, /apt-get clean/)
        assert.match(runtime, /rm -rf \/var\/lib\/apt\/lists\/\*/)
        assert.match(runtime, /Ubuntu security updates/)
    }
})
