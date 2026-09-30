import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

/** 仓库根解析：`KK_STUDIO_REPO_ROOT` 优先，否则从本文件位置向上寻找 worktree 根（`.git` 文件
 * 或目录），因此移动本文件不会改变结果。 */
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

const REPOSITORY_ROOT = repositoryRoot()

const WORKFLOWS = [
  '.github/workflows/daemon-release.yml',
  '.github/workflows/docker-publish.yml',
  '.github/workflows/process-scope.yml',
]

/**
 * 第三方 GitHub Action 的唯一事实源：每个 major tag 固定到它当前解析出的完整 commit SHA，
 * 并保留 `# vN` 说明。SHA 由 `git ls-remote https://github.com/<owner>/<repo> refs/tags/vN`
 * 逐项验证后记录在这里；升级只允许同步 SHA/注释，不允许退回可变 tag。
 */
const PINNED_ACTIONS = new Map([
  ['actions/checkout', { sha: '11d5960a326750d5838078e36cf38b85af677262', version: 'v4' }],
  ['actions/setup-java', { sha: 'cf277c60eb25467037889841efdb72551f06f6c3', version: 'v4' }],
  ['actions/setup-node', { sha: '49933ea5288caeca8642d1e84afbd3f7d6820020', version: 'v4' }],
  ['actions/upload-artifact', { sha: 'ea165f8d65b6e75b540449e92b4886f43607fa02', version: 'v4' }],
  ['actions/download-artifact', { sha: 'd3f86a106a0bac45b974a628896c90dbdf5c8093', version: 'v4' }],
  ['docker/setup-buildx-action', { sha: '8d2750c68a42422c14e847fe6c8ac0403b4cbd6f', version: 'v3' }],
  ['docker/login-action', { sha: 'c94ce9fb468520275223c153574b00df6fe4bcc9', version: 'v3' }],
  ['docker/build-push-action', { sha: '10e90e3645eae34f1e60eeb005ba3a3d33f178e8', version: 'v6' }],
])

const USES_STEP = /^\s+(?:-\s+)?uses:/gm
const USES_REFERENCE = /^\s+(?:-\s+)?uses:\s*(\S+)(?:\s+#\s*(\S+))?\s*$/gm

test('test intent: every third-party GitHub Action is pinned to a verified commit SHA', () => {
  // 逐行扫描全部工作流：tag/branch 引用、缺失 `# vN` 说明、或未登记的 action 都会失败，
  // 避免 CI 静默执行可变代码。
  let pinnedCount = 0
  for (const relativePath of WORKFLOWS) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')

    const stepCount = [...source.matchAll(USES_STEP)].length
    const references = [...source.matchAll(USES_REFERENCE)]
    assert.equal(
      references.length,
      stepCount,
      `${relativePath}: every uses: step must use "<owner>/<repo>@<sha> # vN" exactly`,
    )

    for (const [, reference, versionComment] of references) {
      const separator = reference.lastIndexOf('@')
      assert.ok(separator > 0, `${relativePath}: ${reference} must be <owner>/<repo>@<ref>`)
      const action = reference.slice(0, separator)
      const revision = reference.slice(separator + 1)

      const expected = PINNED_ACTIONS.get(action)
      assert.ok(expected, `${relativePath}: ${action} is not an approved third-party action`)
      assert.match(
        revision,
        /^[0-9a-f]{40}$/u,
        `${relativePath}: ${action} must pin a full 40-char commit SHA, got ${revision}`,
      )
      assert.equal(
        revision,
        expected.sha,
        `${relativePath}: ${action} drifted from the verified commit SHA`,
      )
      assert.equal(
        versionComment,
        expected.version,
        `${relativePath}: ${action} must keep the "${expected.version}" comment`,
      )
      pinnedCount += 1
    }
  }

  // 防止三条工作流被清空或 `uses:` 被整体移走后守卫静默通过。
  assert.ok(pinnedCount > 0, 'at least one pinned action reference must be present')
})
