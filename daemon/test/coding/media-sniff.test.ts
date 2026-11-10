import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { detectMediaMimeType } from '../../src/coding/media-sniff.js'

/**
 * 与 Java MediaTypeSniffer 共用 platform/runtime/src/test/resources 下的二进制样例：
 * 正例必须判为对应格式，伪装扩展名反例的字节特征不成立，不得判为对应格式。
 */
const MEDIA_SAMPLES = fileURLToPath(
  new URL(
    '../../../platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/storage/media/',
    import.meta.url,
  ),
)

function sample(name: string): Uint8Array {
  return new Uint8Array(readFileSync(join(MEDIA_SAMPLES, name)))
}

describe('detectMediaMimeType 与 Java MediaTypeSniffer 共用样例', () => {
  it('按文件头识别 MP4/MOV/WAV/MP3/PDF 正例', () => {
    expect(detectMediaMimeType(sample('video/sample.mp4'))).toBe('video/mp4')
    expect(detectMediaMimeType(sample('video/sample.mov'))).toBe('video/quicktime')
    expect(detectMediaMimeType(sample('audio/sample.wav'))).toBe('audio/wav')
    expect(detectMediaMimeType(sample('audio/sample-id3.mp3'))).toBe('audio/mpeg')
    expect(detectMediaMimeType(sample('audio/sample-frame.mp3'))).toBe('audio/mpeg')
    expect(detectMediaMimeType(sample('doc/sample.pdf'))).toBe('application/pdf')
  })

  it('伪装扩展名反例不判为对应格式', () => {
    for (const [name, claimed] of [
      ['video/sample.mp4.fake', 'video/mp4'],
      ['video/sample.mov.fake', 'video/mp4'],
      ['audio/sample.wav.fake', 'audio/wav'],
      ['audio/sample-id3.mp3.fake', 'audio/mpeg'],
      ['audio/sample-frame.mp3.fake', 'audio/mpeg'],
      ['doc/sample.pdf.fake', 'application/pdf'],
    ] as const) {
      const sniffed = detectMediaMimeType(sample(name))
      expect(sniffed === null || sniffed !== claimed, `${name} must not be ${claimed}`).toBe(true)
    }
  })

  it('过短或无特征的字节返回 null', () => {
    expect(detectMediaMimeType(new Uint8Array(0))).toBeNull()
    expect(detectMediaMimeType(new Uint8Array([0x01, 0x02, 0x03]))).toBeNull()
    expect(detectMediaMimeType(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBeNull()
  })
})
