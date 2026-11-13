import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import path from 'node:path'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const root = repositoryRoot()
const goCi = readFileSync(path.join(root, '.github/workflows/daemon-go-ci.yml'), 'utf8')
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

test('Go daemon CI builds all six static targets and is reusable by release and Docker workflows', () => {
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
  assert.match(release, /uses: \.\/\.github\/workflows\/daemon-go-ci\.yml/)
  assert.match(docker, /uses: \.\/\.github\/workflows\/daemon-go-ci\.yml/)
})

test('reusable Go CI runs the bundled IDE extension contract tests', () => {
  assert.match(goCi, /- name: Test IDE bridge extension\n\s+run: node --test daemon-go\/ide-extension\/extension\.test\.cjs/)
})

test('reusable Go CI exercises Jetty interoperability with the Go daemon binary', () => {
  assert.match(goCi, /jetty-interop:/)
  assert.match(goCi, /actions\/setup-go@/)
  assert.match(goCi, /DaemonCrossLanguageIntegrationTest#dataPlaneRoutingHttpAndExtendedConnectReturn404ForNonexistentView/)
  assert.match(javaInterop, /GoDaemonBinary\.build\(repoRoot\(\)/)
  assert.doesNotMatch(goCi, /DAEMON_BINARY/)
})

test('release dispatch stages Go archives and cannot publish', () => {
  assert.match(release, /GO_BIN: go/)
  assert.match(release, /workflow_dispatch:/)
  assert.match(release, /daemon-go\/release/)
  assert.match(release, /github\.event_name == 'push' && startsWith\(github\.ref, 'refs\/tags\/'\)/)
  assert.doesNotMatch(release, /daemon-ci\.yml/)
})

test('release staging builds the Go executable and packages updater-compatible runtime layout', () => {
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

test('Unix and Windows installers execute the Go binary and internal lock helper', () => {
  assert.match(install, /__lock-helper hold/)
  assert.match(install, /__lock-helper wait/)
  assert.match(install, /--config/)
  assert.match(install, /local arguments=\("\$daemon_binary" --config/)
  assert.doesNotMatch(install, /main\.js|node_modules\/koffi|runtime\/code-server\/lib\/node/)
  assert.match(windowsInstall, /kk-studio-daemon\.exe/)
  assert.match(windowsInstall, /__lock-helper/)
  assert.doesNotMatch(windowsInstall, /main\.js|node_modules\\koffi/)
  assert.ok(windowsInstall.includes(String.raw`runtime\code-server\lib\node.exe`))
})

test('archive smoke verifies the Go executable, bundled IDE runtime and excludes legacy payload', () => {
  assert.match(smoke, /kk-studio-daemon/)
  assert.match(smoke, /runtime\/code-server\/out\/node\/entry\.js/)
  assert.match(smoke, /ide-extension\/extension\.cjs/)
  assert.match(smoke, /node_modules/)
  assert.match(smoke, /--version/)
})
