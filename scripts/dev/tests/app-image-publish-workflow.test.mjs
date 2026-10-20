/** Both branches publish the same packaged app image; only the mutable tag differs. */

import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, readText } from './support/shell.mjs'

const WORKFLOW = readText(path.join(REPOSITORY_ROOT, '.github/workflows/docker-publish.yml'))

test('test intent: dev and main build one Dockerfile and push one image repository', () => {
  assert.ok(WORKFLOW.includes('file: deploy/local/Dockerfile'))
  // Assembled so this file does not itself count as a live reference to the removed node.
  assert.ok(!WORKFLOW.includes('deploy/' + 'dev'))
  assert.ok(!WORKFLOW.includes('kk-studio-' + 'dev'))
  assert.ok(WORKFLOW.includes('${{ secrets.DOCKERHUB_NAMESPACE }}/kk-studio:${{ steps.target.outputs.tag }}'))
  assert.ok(WORKFLOW.includes('${{ secrets.DOCKERHUB_NAMESPACE }}/kk-studio:${{ github.sha }}'))
})

test('test intent: the mutable tag is selected fail-closed from the pushed branch', () => {
  // Only main/dev yield a mutable tag; every other ref must fail instead of guessing.
  for (const branch of ['main', 'dev']) {
    assert.match(WORKFLOW, new RegExp(`^\\s+${branch}\\)\\n\\s+echo "tag=${branch}"`, 'm'))
  }
  assert.ok(WORKFLOW.includes('echo "unsupported publish branch: ${GITHUB_REF_NAME}" >&2'))
})

test('test intent: push validation stays main-only and credentials stay in secrets', () => {
  // Push gates stay on main; manual validation is opt-in and never a build credential.
  const gate =
    "    if: github.ref_name == 'main' || (github.event_name == 'workflow_dispatch' && inputs.validate_only)\n"
  assert.equal(WORKFLOW.split(gate).length - 1, 4)
  assert.ok(WORKFLOW.includes("needs.validate.result == 'skipped'"))
  assert.ok(WORKFLOW.includes('!cancelled()'))
  assert.ok(WORKFLOW.includes('password: ${{ secrets.DOCKERHUB_TOKEN }}'))
  assert.ok(!WORKFLOW.includes('build-args'))
})
