import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import type { ReleaseArch, ReleaseOs } from '../../src/release.js'

/** 五个已发布平台的一条官方制品事实（与 Java 制品生产方共享同一份 fixture）。 */
export interface AcceptedArtifact {
  name: string
  operatingSystem: ReleaseOs
  architecture: ReleaseArch
  artifactFileName: string
  artifactUrl: string
  checksumUrl: string
}

/** 必须被入口拒绝的错配形态。 */
export interface RejectedArtifact {
  name: string
  artifactUrl: string
}

export interface UpdateArtifactFixture {
  targetVersion: string
  operationId: string
  artifactSha256: string
  releaseBase: string
  accepted: AcceptedArtifact[]
  rejected: RejectedArtifact[]
}

/**
 * 共享的官方制品 fixture：协议入口（Node 与 Java）与 Java 制品生产方都以此为准，
 * 因此「入口接受、更新器拒绝」这类错配不可能只在一侧被发现。
 */
export const artifactFixture: UpdateArtifactFixture = JSON.parse(
  readFileSync(fileURLToPath(new URL('../resources/update/official-artifact-fixtures.json', import.meta.url)), 'utf8'),
) as UpdateArtifactFixture
