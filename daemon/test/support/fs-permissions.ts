import { execFile } from 'node:child_process'
import { chmod } from 'node:fs/promises'
import { promisify } from 'node:util'

const run = promisify(execFile)

/**
 * POSIX mode 在 Windows 上不是访问控制。测试要制造不可读、外部可读或只读时，
 * 按当前平台落成真实语义：POSIX 用 mode，Windows 用 icacls/attrib，并且不回显 ACL 输出。
 */

function currentUser(): string {
  const name = process.env.USERNAME ?? process.env.USER
  if (name === undefined || name.trim() === '') {
    throw new Error('current user is unavailable')
  }
  return name
}

async function icacls(target: string, action: '/deny' | '/grant' | '/reset', entry?: string): Promise<void> {
  const args = entry === undefined ? [target, action] : [target, action, entry]
  try {
    await run('icacls', args, { windowsHide: true })
  } catch {
    throw new Error(`icacls ${action} failed`)
  }
}

/** 只拒绝读数据或列目录，保留 lstat 所需的属性读取。POSIX 仍是 mode 000。 */
export async function denyRead(target: string): Promise<void> {
  if (process.platform === 'win32') {
    await icacls(target, '/deny', `${currentUser()}:(RD)`)
    return
  }
  await chmod(target, 0o000)
}

/** 恢复测试前的可读状态，便于清理临时目录。 */
export async function restoreRead(target: string): Promise<void> {
  if (process.platform === 'win32') {
    await icacls(target, '/reset')
    return
  }
  await chmod(target, 0o755)
}

/** 给 Windows 对象加上内置 World SID 的读取权限，形成外部 ACE。 */
export async function grantEveryoneRead(target: string): Promise<void> {
  if (process.platform !== 'win32') {
    throw new Error('foreign ACE fixtures are Windows-only')
  }
  await icacls(target, '/grant', '*S-1-1-0:(R)')
}

/** 标记只读：POSIX 精确到 0444，Windows 使用文件只读属性。 */
export async function markReadOnly(target: string): Promise<void> {
  if (process.platform === 'win32') {
    try {
      await run('attrib', ['+R', target], { windowsHide: true })
    } catch {
      throw new Error('attrib +R failed')
    }
    return
  }
  await chmod(target, 0o444)
}

/** 清除只读标记，避免临时文件无法删除。 */
export async function clearReadOnly(target: string): Promise<void> {
  if (process.platform === 'win32') {
    try {
      await run('attrib', ['-R', target], { windowsHide: true })
    } catch {
      throw new Error('attrib -R failed')
    }
    return
  }
  await chmod(target, 0o644)
}
