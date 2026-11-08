import { hostname, homedir, platform, tmpdir, userInfo } from 'node:os'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { create } from '@bufbuild/protobuf'

import {
  Architecture,
  HostInfoSchema,
  OperatingSystem,
  type HostInfo,
} from './generated/control_pb.js'

/** 采集真实进程宿主事实：OS family、架构、时区、进程用户、canonical HOME 与操作系统临时目录。 */
export function detectEnvironmentInfo(): HostInfo {
  return create(HostInfoSchema, {
    operatingSystem: detectOperatingSystem(),
    architecture: detectArchitecture(),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    userName: safeUserName(),
    homeDirectory: canonicalHomeDirectory(homedir()),
    tempDirectory: tmpdir(),
  })
}

/** 把 Node 的 process.arch 归一到发行架构枚举；不支持的架构失败关闭，绝不猜测。 */
function detectArchitecture(): Architecture {
  switch (process.arch) {
    case 'x64':
      return Architecture.AMD64
    case 'arm64':
      return Architecture.ARM64
    default:
      throw new Error(`unsupported daemon architecture: ${process.arch}`)
  }
}

function detectOperatingSystem(): OperatingSystem {
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
