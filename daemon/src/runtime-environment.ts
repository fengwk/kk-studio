import { hostname, homedir, platform, userInfo } from 'node:os'
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import type { DaemonConfig } from './contracts.js'
import type { EnvironmentInfo } from './protocol.js'

/** 采集真实进程宿主事实：OS family、时区、进程用户、canonical HOME、稳定说明与受控临时目录。 */
export function detectEnvironmentInfo(config: DaemonConfig, tempDirectory?: string): EnvironmentInfo {
  const operatingSystem = detectOperatingSystem()
  const home = canonicalHomeDirectory(homedir())
  return {
    operatingSystem,
    architecture: detectArchitecture(),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    userName: safeUserName(),
    homeDirectory: home,
    note: config.note ?? defaultNote(operatingSystem),
    tempDirectory: tempDirectory ?? join(resolve(config.dataDir), 'tmp'),
  }
}

/** 把 Node 的 process.arch 归一到发行制品命名；不支持的架构失败关闭，绝不猜测。 */
export function detectArchitecture(): EnvironmentInfo['architecture'] {
  switch (process.arch) {
    case 'x64':
      return 'amd64'
    case 'arm64':
      return 'arm64'
    default:
      throw new Error(`unsupported daemon architecture: ${process.arch}`)
  }
}

function detectOperatingSystem(): EnvironmentInfo['operatingSystem'] {
  const name = platform()
  if (name === 'win32') {
    return 'windows'
  }
  if (name === 'darwin') {
    return 'macos'
  }
  if (name === 'linux') {
    const env = process.env
    if (
      (env.WSL_DISTRO_NAME !== undefined && env.WSL_DISTRO_NAME !== '') ||
      (env.WSL_INTEROP !== undefined && env.WSL_INTEROP !== '') ||
      procContainsMicrosoft('/proc/version') ||
      procContainsMicrosoft('/proc/sys/kernel/osrelease')
    ) {
      return 'wsl'
    }
    return 'linux'
  }
  throw new Error(`unsupported daemon operating system: ${name}`)
}

function procContainsMicrosoft(path: string): boolean {
  try {
    return readFileSync(path, 'utf8').toLowerCase().includes('microsoft')
  } catch {
    return false
  }
}

function canonicalHomeDirectory(home: string): string {
  try {
    return resolve(home)
  } catch {
    return home
  }
}

function safeUserName(): string {
  try {
    return userInfo().username
  } catch {
    return process.env.USER ?? process.env.USERNAME ?? hostname()
  }
}

/** 按实测 OS 生成稳定默认说明；不构成 cwd、默认 workdir 或沙箱。 */
export function defaultNote(operatingSystem: EnvironmentInfo['operatingSystem']): string {
  switch (operatingSystem) {
    case 'windows':
      return 'Windows environment.'
    case 'wsl':
      return 'WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows commands may be invocable from WSL.'
    case 'linux':
      return 'Linux environment.'
    case 'macos':
      return 'macOS environment.'
  }
}
