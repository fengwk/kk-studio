/**
 * 受管发行的平台矩阵与版本字符集事实源；发布制品的命名由发布脚本维护。
 */

export type ReleaseOs = 'linux' | 'macos' | 'windows'

export type ReleaseArch = 'amd64' | 'arm64'

/** 官方发布矩阵：daemon 只接受这五个 (os, arch) 组合，绝不让不存在的平台被推导出来。 */
const PUBLISHED_PLATFORMS = new Set([
  'linux-amd64',
  'linux-arm64',
  'macos-amd64',
  'macos-arm64',
  'windows-amd64',
])

/** Daemon 版本字符集；wire 校验、UPDATE 目标与运行版本共用。 */
export const DAEMON_VERSION_PATTERN = /^[0-9A-Za-z._+-]{1,64}$/

/** 取得当前宿主平台的官方发布矩阵键（linux-amd64/linux-arm64/macos-amd64/macos-arm64/windows-amd64）。 */
export function currentPlatformKey(platformName: NodeJS.Platform = process.platform, archName: string = process.arch): string {
  let os: ReleaseOs
  if (platformName === 'linux') {
    os = 'linux'
  } else if (platformName === 'darwin') {
    os = 'macos'
  } else if (platformName === 'win32') {
    os = 'windows'
  } else {
    throw new Error(`unsupported daemon operating system: ${platformName}`)
  }
  let arch: ReleaseArch
  if (archName === 'x64') {
    arch = 'amd64'
  } else if (archName === 'arm64') {
    arch = 'arm64'
  } else {
    throw new Error(`unsupported daemon architecture: ${archName}`)
  }
  const key = `${os}-${arch}`
  if (!PUBLISHED_PLATFORMS.has(key)) {
    throw new Error(`platform is not covered by the official release matrix: ${key}`)
  }
  return key
}

