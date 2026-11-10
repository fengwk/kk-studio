import { PROBE_BYTES } from './text.js'

/**
 * Daemon 侧媒体类型判定：只看文件头 magic 特征，从不信任扩展名。
 *
 * <p>规则与 Java `MediaTypeSniffer` 对同一组共用样例（platform/runtime/src/test/resources 下的
 * `fun/fengwk/kkstudio/platform/storage/media`）保持一致；本模块只覆盖音视频与 PDF 的原样上传
 * 通道，图片仍由 `detectImageMediaType` 判定，其余未识别字节维持“看似二进制”的明确拒绝。
 */

export type MediaMimeType =
  | 'audio/mpeg'
  | 'audio/wav'
  | 'video/mp4'
  | 'video/quicktime'
  | 'application/pdf'

const MP3_SYNC_SECOND_BYTES = new Set([0xfb, 0xf3, 0xf2])

/** 按文件头 magic 判定音视频/PDF 媒体类型；无法判定时返回 null。 */
export function detectMediaMimeType(head: Uint8Array): MediaMimeType | null {
  if (head.length < 4) {
    return null
  }
  if (startsWithAscii(head, '%PDF')) {
    return 'application/pdf'
  }
  if (startsWithAscii(head, 'RIFF') && head.length >= 12) {
    if (ascii(head, 8, 4) === 'WAVE') {
      return 'audio/wav'
    }
    return null
  }
  if (startsWithAscii(head, 'ID3')) {
    return 'audio/mpeg'
  }
  if (head.length >= 12 && ascii(head, 4, 4) === 'ftyp') {
    const brand = ascii(head, 8, 4).toLowerCase()
    if (brand.startsWith('m4a') || brand.startsWith('mp4a')) {
      // 音频 ISO 容器不属于本切片的音视频原样上传通道。
      return null
    }
    if (brand.startsWith('qt')) {
      return 'video/quicktime'
    }
    return 'video/mp4'
  }
  if (head[0] === 0xff && MP3_SYNC_SECOND_BYTES.has((head[1] as number) & 0xf7)) {
    return 'audio/mpeg'
  }
  return null
}

/** 读取文件头探测字节（与文本探测共用 PROBE_BYTES 窗口）。 */
export { PROBE_BYTES }

function startsWithAscii(head: Uint8Array, prefix: string): boolean {
  if (head.length < prefix.length) {
    return false
  }
  for (let index = 0; index < prefix.length; index++) {
    if (head[index] !== (prefix.charCodeAt(index) & 0xff)) {
      return false
    }
  }
  return true
}

function ascii(head: Uint8Array, offset: number, length: number): string {
  if (offset + length > head.length) {
    return ''
  }
  let text = ''
  for (let index = offset; index < offset + length; index++) {
    text += String.fromCharCode(head[index] as number)
  }
  return text
}
