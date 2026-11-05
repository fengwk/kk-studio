import { hostname, homedir, platform, tmpdir, userInfo } from 'node:os'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { create } from '@bufbuild/protobuf'

import type { DaemonConfig } from './contracts.js'
import {
  Architecture,
  EnvironmentInfoSchema,
  OperatingSystem,
  type EnvironmentInfo,
} from './generated/control_pb.js'

/** 采集真实进程宿主事实：OS family、时区、进程用户、canonical HOME、稳定说明与操作系统临时目录。 */
export function detectEnvironmentInfo(config: DaemonConfig): EnvironmentInfo {
  const operatingSystem = detectOperatingSystem()
  const home = canonicalHomeDirectory(homedir())
  return create(EnvironmentInfoSchema, {
    operatingSystem,
    architecture: detectArchitecture(),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    userName: safeUserName(),
    homeDirectory: home,
    note: config.note ?? defaultNote(operatingSystem),
    tempDirectory: tmpdir(),
  })
}

/** 把 Node 的 process.arch 归一到发行架构枚举；不支持的架构失败关闭，绝不猜测。 */
export function detectArchitecture(): Architecture {
  switch (process.arch) {
    case 'x64':
      return Architecture.AMD64
    case 'arm64':
      return Architecture.ARM64
    default:
      throw new Error(`unsupported daemon architecture: ${process.arch}`)
  }
}

export function detectOperatingSystem(): OperatingSystem {
  const name = platform()
  if (name === 'win32') {
    return OperatingSystem.WINDOWS
  }
  if (name === 'darwin') {
    return OperatingSystem.MACOS
  }
  if (name === 'linux') {
    const env = process.env
    if (
      (env.WSL_DISTRO_NAME !== undefined && env.WSL_DISTRO_NAME !== '') ||
      (env.WSL_INTEROP !== undefined && env.WSL_INTEROP !== '') ||
      procContainsMicrosoft('/proc/version') ||
      procContainsMicrosoft('/proc/sys/kernel/osrelease')
    ) {
      return OperatingSystem.WSL
    }
    return OperatingSystem.LINUX
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
export function defaultNote(operatingSystem: OperatingSystem): string {
  switch (operatingSystem) {
    case OperatingSystem.WINDOWS:
      return 'Windows environment.'
    case OperatingSystem.WSL:
      return 'WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows commands may be invocable from WSL.'
    case OperatingSystem.LINUX:
      return 'Linux environment.'
    case OperatingSystem.MACOS:
      return 'macOS environment.'
    case OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED:
      throw new Error('operatingSystem must not be unspecified')
  }
}
