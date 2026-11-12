import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { repositoryRoot } from '../../../lib/repo-root.mjs'

const root = repositoryRoot()
const goCi = readFileSync(`${root}/.github/workflows/daemon-go-ci.yml`, 'utf8')
const release = readFileSync(`${root}/.github/workflows/daemon-release.yml`, 'utf8')
const docker = readFileSync(`${root}/.github/workflows/docker-publish.yml`, 'utf8')

test('Go daemon CI is reusable and compiles all six supported static targets', () => {
  assert.match(goCi, /workflow_call:/)
  assert.match(goCi, /CGO_ENABLED=0 go build/)
  for (const target of ['linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64', 'windows/amd64', 'windows/arm64']) {
    assert.ok(goCi.includes(target), `missing target ${target}`)
  }
})

test('release and Docker gates reuse Go daemon CI', () => {
  for (const workflow of [release, docker]) {
    assert.match(workflow, /uses: \.\/\.github\/workflows\/daemon-go-ci\.yml/)
    assert.doesNotMatch(workflow, /daemon-ci\.yml/)
  }
})

test('manual release dispatch stages and smokes but does not publish', () => {
  assert.match(release, /workflow_dispatch:/)
  assert.match(release, /daemon_ci:\n    name: Go daemon CI\n    if: github\.event_name == 'push'/)
  assert.match(release, /stage:\n    name: build and stage daemon release\n    needs:\n      - daemon_ci\n    if: \$\{\{ always\(\) && \(github\.event_name == 'workflow_dispatch' \|\| needs\.daemon_ci\.result == 'success'\) \}\}/)
  assert.match(release, /daemon-go\/release/)
  assert.match(release, /if: \$\{\{ github\.event_name == 'push' && startsWith\(github\.ref, 'refs\/tags\/'\) \}\}/)
  assert.match(release, /smoke_archives:/)
  assert.match(release, /name: Check documentation layout\n        if: github\.event_name != 'workflow_dispatch'/)
})
