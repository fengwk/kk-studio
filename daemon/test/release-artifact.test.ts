import { describe, expect, it } from 'vitest'

import {
  PUBLISHED_PLATFORMS,
  artifactFileName,
  artifactUrl,
  checksumUrl,
  parseReleaseArtifactUrl,
} from '../src/release.js'
import { artifactFixture } from './support/update-fixture.js'

/**
 * 官方制品身份的唯一事实：命名、URL 与解析。
 *
 * 覆盖共享 fixture 的全部正例（五个已发布平台的官方 tar.gz）与反例（旧 jar、错 tag/版本、错平台、任意 URL），
 * 使「协议入口接受、更新器拒绝」这类两侧各判一套的错配不可能再发生。
 */
describe('release artifact identity', () => {
  it('covers exactly the published platform matrix in the shared fixture', () => {
    const platforms = artifactFixture.accepted.map(entry => `${entry.operatingSystem}-${entry.architecture}`)
    expect([...platforms].sort()).toEqual([...PUBLISHED_PLATFORMS].sort())
  })

  it.each(artifactFixture.accepted)('names and parses the official $name archive', (entry) => {
    const file = artifactFileName(artifactFixture.targetVersion, entry.operatingSystem, entry.architecture)
    expect(file).toBe(entry.artifactFileName)
    expect(artifactUrl(artifactFixture.targetVersion, entry.operatingSystem, entry.architecture)).toBe(entry.artifactUrl)
    expect(checksumUrl(artifactFixture.targetVersion, entry.operatingSystem, entry.architecture)).toBe(entry.checksumUrl)
    expect(parseReleaseArtifactUrl(entry.artifactUrl)).toEqual({
      targetVersion: artifactFixture.targetVersion,
      os: entry.operatingSystem,
      arch: entry.architecture,
    })
  })

  it.each(artifactFixture.rejected)('refuses $name', (entry) => {
    expect(parseReleaseArtifactUrl(entry.artifactUrl)).toBeNull()
  })

  it('refuses structural shapes that merely keep the official prefix', () => {
    const base = artifactFixture.releaseBase
    expect(parseReleaseArtifactUrl(`${base}/v`)).toBeNull()
    expect(parseReleaseArtifactUrl(`${base}/v1.1.1`)).toBeNull()
    expect(parseReleaseArtifactUrl(`${base}/v1.1.1/`)).toBeNull()
    expect(parseReleaseArtifactUrl(`${base}/v1.1.1/kk-studio-daemon-v1.1.1-linux-amd64.tar`)).toBeNull()
    expect(parseReleaseArtifactUrl(`${base}/v1.1.1/kk-studio-daemon-v1.1.1-linux-amd64.tar.gz/extra`)).toBeNull()
    expect(parseReleaseArtifactUrl(`${base}/v1.1.1/kk-studio-daemon-v1.1.1-linux-amd64.tar.gz#fragment`)).toBeNull()
    expect(parseReleaseArtifactUrl(`${base}/v${'v'.repeat(65)}/kk-studio-daemon-v1.1.1-linux-amd64.tar.gz`)).toBeNull()
  })
})
