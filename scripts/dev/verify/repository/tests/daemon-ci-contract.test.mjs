import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import path from 'node:path'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const root = repositoryRoot()
const goCi = readFileSync(path.join(root, '.github/workflows/daemon-ci.yml'), 'utf8')
const javaInterop = readFileSync(
  path.join(root, 'web/src/test/java/fun/fengwk/kkstudio/web/environment/DaemonCrossLanguageIntegrationTest.java'),
  'utf8',
)
const release = readFileSync(path.join(root, '.github/workflows/daemon-release.yml'), 'utf8')
const docker = readFileSync(path.join(root, '.github/workflows/docker-publish.yml'), 'utf8')
const prepare = readFileSync(path.join(root, 'scripts/daemon/prepare-release.sh'), 'utf8')
const install = readFileSync(path.join(root, 'scripts/daemon/install.sh'), 'utf8')
const windowsInstall = readFileSync(path.join(root, 'scripts/daemon/install.ps1'), 'utf8')
const smoke = readFileSync(path.join(root, 'scripts/daemon/smoke-archive.sh'), 'utf8')

test('daemon CI builds all six static targets and is reusable by release and Docker workflows', () => {
  assert.match(goCi, /workflow_call:/)
  assert.match(goCi, /POSTGRES_VERSION: '17'/)
  assert.match(goCi, /go test -json \.\/\.\.\./)
  assert.match(goCi, /go test -race \.\/\.\.\./)
  assert.doesNotMatch(goCi, /go test -p\s+1/)
  assert.match(goCi, /Install ripgrep for search parity tests[\s\S]*?if: runner\.os == 'Linux'[\s\S]*?sudo apt-get install -y ripgrep/)
  assert.match(goCi, /CGO_ENABLED=0 go build/)
  for (const target of ['linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64', 'windows/amd64', 'windows/arm64']) {
    assert.ok(goCi.includes(target), `missing cross-compile target ${target}`)
  }
  assert.match(release, /uses: \.\/\.github\/workflows\/daemon-ci\.yml/)
  assert.match(docker, /uses: \.\/\.github\/workflows\/daemon-ci\.yml/)
})

test('Daemon CI watches its script suites and local composite actions for both push and pull requests', () => {
  for (const dependency of [
    'scripts/dev/lib/**',
    'scripts/dev/verify/reliability/**',
    'scripts/dev/verify/supply-chain/**',
    '.github/actions/**',
  ]) {
    assert.equal(goCi.split(`- '${dependency}'`).length - 1, 2, `both events must watch ${dependency}`)
  }
})

test('release history scanning requires the preceding release tag as its baseline', () => {
  assert.ok(release.includes("previous=$(git describe --tags --abbrev=0 --match 'v*' 'HEAD^')"))
  assert.match(release, /--remote-pr --fail-on-high --baseline "\$\{previous\}"/)
})

test('reusable Daemon CI runs the bundled IDE extension contract tests', () => {
  assert.match(goCi, /- name: Test IDE bridge extension\n\s+run: node --test daemon\/ide-extension\/extension\.test\.cjs/)
})

test('reusable Daemon CI exercises Jetty interoperability with the Daemon binary', () => {
  assert.match(goCi, /jetty-interop:/)
  assert.match(goCi, /actions\/setup-go@/)
  assert.match(goCi, /DaemonCrossLanguageIntegrationTest#dataPlaneRoutingHttpAndExtendedConnectReturn404ForNonexistentView/)
  assert.match(javaInterop, /DaemonBinary\.build\(repoRoot\(\)/)
  assert.doesNotMatch(goCi, /DAEMON_BINARY/)
})

test('release dispatch stages release archives and cannot publish', () => {
  assert.match(release, /GO_BIN: go/)
  assert.match(release, /workflow_dispatch:/)
  assert.match(release, /daemon\/release/)
  assert.match(release, /github\.event_name == 'push' && startsWith\(github\.ref, 'refs\/tags\/'\)/)
  assert.doesNotMatch(release, /^ {2}(quality|test):/m)
})

test('release staging builds the Daemon executable and packages updater-compatible runtime layout', () => {
  assert.match(prepare, /GOOS="\$goos" GOARCH="\$arch" CGO_ENABLED=0/)
  assert.match(prepare, /-trimpath/)
  assert.match(prepare, /-ldflags/)
  assert.match(prepare, /kk-studio-daemon\.exe/)
  assert.match(prepare, /runtime\/code-server/)
  assert.match(prepare, /find .* -name rg -o -name rg\.exe/)
  assert.match(prepare, /-name 'ripgrep\*'/)
  assert.match(prepare, /ide-extension/)
  assert.match(prepare, /\.sha256/)
  assert.doesNotMatch(prepare, /node_modules|@vscode\/ripgrep/)
})

test('Unix and Windows installers execute the Daemon binary and internal lock helper', () => {
  assert.match(install, /__lock-helper hold/)
  assert.match(install, /__lock-helper wait/)
  assert.match(install, /--config/)
  assert.match(install, /local arguments=\("\$daemon_binary" --config/)
  assert.doesNotMatch(install, /runtime\/code-server\/lib\/node/)
  assert.match(windowsInstall, /kk-studio-daemon\.exe/)
  assert.match(windowsInstall, /__lock-helper/)
  assert.ok(windowsInstall.includes(String.raw`runtime\code-server\lib\node.exe`))
})

test('archive smoke verifies the Daemon executable and bundled IDE runtime', () => {
  assert.match(smoke, /kk-studio-daemon/)
  assert.match(smoke, /runtime\/code-server\/out\/node\/entry\.js/)
  assert.match(smoke, /ide-extension\/extension\.cjs/)
  assert.match(smoke, /--version/)
})
