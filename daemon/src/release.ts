/**
 * 受管发行制品的唯一事实：Release 基址、官方五平台矩阵与制品的命名/解析。
 *
 * <p>协议入口（UPDATE 命令的 artifactUrl 校验）与更新器（下载、校验与发行清单预检）都只从这一处取得官方
 * 制品身份，绝不各自维护一份 URL 形态：否则入口与更新器会各判一套，出现「入口接受、更新器必然拒绝」的错配。
 */

/** 官方 Release 下载基址；只允许该仓库。 */
export const RELEASE_BASE = 'https://github.com/fengwk/kk-studio/releases/download'

export type ReleaseOs = 'linux' | 'macos' | 'windows'

export type ReleaseArch = 'amd64' | 'arm64'

/** 官方发布矩阵：daemon 只接受这五个 (os, arch) 组合，绝不让不存在的制品被推导出来。 */
export const PUBLISHED_PLATFORMS = new Set([
  'linux-amd64',
  'linux-arm64',
  'macos-amd64',
  'macos-arm64',
  'windows-amd64',
])

/** 制品文件名：{@code kk-studio-daemon-v<version>-<os>-<arch>.tar.gz}。 */
export function artifactFileName(version: string, os: ReleaseOs, arch: ReleaseArch): string {
  return `kk-studio-daemon-v${version}-${os}-${arch}.tar.gz`
}

/** 官方制品下载 URL。 */
export function artifactUrl(version: string, os: ReleaseOs, arch: ReleaseArch): string {
  return `${RELEASE_BASE}/v${version}/${artifactFileName(version, os, arch)}`
}

/** 官方校验文件下载 URL。 */
export function checksumUrl(version: string, os: ReleaseOs, arch: ReleaseArch): string {
  return `${artifactUrl(version, os, arch)}.sha256`
}

/** 官方制品身份：发行 tag 与制品版本一致，且平台必须落在官方发布矩阵内。 */
export interface ReleaseArtifact {
  /** 制品版本，同时是 Release tag（{@code v<version>}）中的版本。 */
  targetVersion: string
  os: ReleaseOs
  arch: ReleaseArch
}

/** 官方制品名：版本字符集与协议 daemonVersion 一致，平台只能是五个已发布组合之一。 */
const ARTIFACT_NAME_PATTERN =
  /^kk-studio-daemon-v([0-9A-Za-z._+-]{1,64})-(linux|macos|windows)-(amd64|arm64)\.tar\.gz$/

/**
 * 解析官方制品 URL：只有「官方仓库 + {@code v<tag>} + 官方归档名 + tag 与制品版本一致 + 已发布平台」才解析出结果，
 * 其余（其它仓库、任意 URL、.jar 等旧形态、未发布平台、附加查询串或额外路径段）一律返回 {@code null}。
 */
export function parseReleaseArtifactUrl(url: string): ReleaseArtifact | null {
  const prefix = `${RELEASE_BASE}/v`
  if (!url.startsWith(prefix)) {
    return null
  }
  const rest = url.slice(prefix.length)
  const separator = rest.indexOf('/')
  if (separator <= 0) {
    return null
  }
  const tag = rest.slice(0, separator)
  const match = ARTIFACT_NAME_PATTERN.exec(rest.slice(separator + 1))
  if (match === null) {
    return null
  }
  const version = match[1]
  const os = match[2]
  const arch = match[3]
  if (version === undefined || os === undefined || arch === undefined || version !== tag) {
    return null
  }
  if (!PUBLISHED_PLATFORMS.has(`${os}-${arch}`)) {
    return null
  }
  return { targetVersion: version, os: os as ReleaseOs, arch: arch as ReleaseArch }
}
