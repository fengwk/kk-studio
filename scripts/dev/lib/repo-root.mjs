/**
 * 仓库根解析：dev 能力之间私有的共享自动化实现（dev/lib/），不是公开入口。
 * 解析不依赖调用方 cwd 或固定目录深度。
 */

import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 返回 kk-studio 仓库根：`KK_STUDIO_REPO_ROOT` 优先，否则从本模块位置向上寻找 worktree 根
 * （`.git` 文件或目录）。结果既不依赖调用方所在目录，也不依赖任何固定目录深度。
 */
export function repositoryRoot() {
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

export const REPO_ROOT = repositoryRoot()
