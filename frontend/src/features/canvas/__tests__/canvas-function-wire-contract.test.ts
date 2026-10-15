import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { decodeCanvasFunctionVersion, isCanvasFunctionVersion } from '@/shared/api/studio-codec'
import { isCanonicalUUID } from '@/features/canvas/function-run'

/**
 * 与 Java {@code CanvasFunctionWireContractTest} 共用的同一份生产 mapper golden：前端必须用严格解码器逐值解析，
 * 证明生成的 Canvas 版本/请求契约与后端实际 wire 一致（而不是手抄样本）。
 */
const GOLDEN_DIR = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../../../web/src/test/resources/fun/fengwk/kkstudio/web/canvas',
)

function golden(filename: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(readFileSync(resolve(GOLDEN_DIR, filename), 'utf8'))
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${filename} must be a JSON object`)
  }
  return parsed as Record<string, unknown>
}

describe('canvas function HTTP contract', () => {
  // 版本身份的 epoch 是 Java long 文本、指纹是 lowercase SHA-256；严格解码器逐值接受。
  it('decodes the production-mapper version golden strictly', () => {
    const raw = golden('canvas-function-version-http.json')
    expect(isCanvasFunctionVersion(raw)).toBe(true)
    expect(decodeCanvasFunctionVersion(raw)).toEqual({
      catalogEpoch: '1',
      definitionFingerprint: '3c9a6c2b5f1d4e8a7b0c9d2e3f4a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6',
    })
    expect(raw.catalogEpoch).toBe('1')
  })

  // start 请求恰好是 {requestId, expectedVersion}；expectedVersion 是同一份严格版本片段。
  it('validates the production-mapper start request golden', () => {
    const raw = golden('canvas-function-run-request-http.json')
    expect(Object.keys(raw).sort()).toEqual(['expectedVersion', 'requestId'])
    expect(isCanonicalUUID(raw.requestId)).toBe(true)
    expect(isCanvasFunctionVersion(raw.expectedVersion)).toBe(true)
    expect(decodeCanvasFunctionVersion(raw.expectedVersion)).toEqual(
      decodeCanvasFunctionVersion(golden('canvas-function-version-http.json')),
    )
  })

  // cancel 请求只携带 requestId，绝不混入版本围栏。
  it('validates the production-mapper cancel request golden', () => {
    const raw = golden('canvas-function-cancel-request-http.json')
    expect(Object.keys(raw)).toEqual(['requestId'])
    expect(isCanonicalUUID(raw.requestId)).toBe(true)
  })
})
