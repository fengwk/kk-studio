import { describe, expect, it } from 'vitest'
import {
  formatSessionFileTime,
  isImageFileName,
  parseSessionFileTextWindow,
  parseSizeBytes,
  sessionFilePath,
} from '@/features/chat/session-files/session-file-format'

const SESSION_ID = '11111111-2222-4333-8444-555555555555'

describe('session file format helpers', () => {
  it('builds session vfs paths without duplicating the separator', () => {
    expect(sessionFilePath(SESSION_ID, 'notes.txt')).toBe(`vfs:/sessions/${SESSION_ID}/notes.txt`)
    expect(sessionFilePath(SESSION_ID, '/docs/')).toBe(`vfs:/sessions/${SESSION_ID}/docs/`)
  })

  /**
   * 测试意图：只有常见图片扩展名进入图片预览，文本类扩展名与无扩展名文件
   * 必须继续走文本窗口，避免把源码或 Markdown 当图片请求。
   */
  it('recognises image file names by extension', () => {
    for (const name of ['photo.png', 'PHOTO.JPG', 'scan.jpeg', 'anim.gif', 'vector.svg']) {
      expect(isImageFileName(name)).toBe(true)
    }
    for (const name of ['notes.txt', 'readme', 'archive.tar.gz', '.env', 'trailing.']) {
      expect(isImageFileName(name)).toBe(false)
    }
  })

  /**
   * 测试意图：解析 read 同源的窗口文本，保留编号正文并提取 range、截断与下一页坐标；
   * 截断标记不能混进正文。
   */
  it('parses a truncated text window with the next position', () => {
    const window = parseSessionFileTextWindow([
      `path: vfs:/sessions/${SESSION_ID}/notes.txt`,
      'ends_with_newline: yes',
      'range: 1:1-2:9',
      'truncated: yes',
      'truncation_reason: line_limit',
      'next: 3:1',
      '',
      '1|alpha',
      '2|beta',
      '',
      '[TRUNCATED: More file content remains. Next position: line 3, column 1.]',
    ].join('\n'))

    expect(window.range).toBe('1:1-2:9')
    expect(window.truncated).toBe(true)
    expect(window.next).toEqual({ offset: 3, columnOffset: 1 })
    expect(window.body).toBe('1|alpha\n2|beta')
  })

  /**
   * 测试意图：空窗口与无截断窗口都要收敛为安全的展示结果；格式不符时保留原文，
   * 不能因为解析失败而隐藏文件内容。
   */
  it('handles empty ranges and format drift without losing content', () => {
    expect(parseSessionFileTextWindow([
      'path: vfs:/sessions/x/empty.txt',
      'ends_with_newline: no',
      'range: empty',
    ].join('\n'))).toEqual({ range: null, truncated: false, next: null, body: '' })

    expect(parseSessionFileTextWindow('unexpected payload\nwith lines')).toEqual({
      range: null,
      truncated: false,
      next: null,
      body: 'unexpected payload\nwith lines',
    })
  })

  it('parses decimal byte sizes and formats epoch millis', () => {
    expect(parseSizeBytes('0')).toBe(0)
    expect(parseSizeBytes('1024')).toBe(1024)
    expect(parseSizeBytes('01')).toBeNull()
    expect(parseSizeBytes('-1')).toBeNull()
    expect(parseSizeBytes(undefined)).toBeNull()

    expect(formatSessionFileTime('1790812800000')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
    expect(formatSessionFileTime('not-a-number')).toBeNull()
    expect(formatSessionFileTime(null)).toBeNull()
  })
})
