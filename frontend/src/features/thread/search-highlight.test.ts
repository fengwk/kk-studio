import { describe, expect, it } from 'vitest'
import { highlightSegments, searchExcerpt } from '@/features/thread/search-highlight'

describe('search highlight', () => {
  it('splits a preview into merged highlight segments', () => {
    const segments = highlightSegments('fix the parser parser bug', ['parser', 'fix'])
    expect(segments.map((segment) => segment.text).join('')).toBe('fix the parser parser bug')
    expect(segments.filter((segment) => segment.match).map((segment) => segment.text))
      .toEqual(['fix', 'parser', 'parser'])
    expect(highlightSegments('nothing', ['parser'])).toEqual([{ text: 'nothing', match: false }])
  })

  // 测试意图：单行省略只显示开头，命中词靠后时摘录要从命中处附近开始，窄行也能看到命中。
  it('starts the excerpt shortly before a deep hit and keeps early hits in place', () => {
    const subtitle = '2026-11-22 10:08 · 空闲 · 结论 服务停机时先停止接收新请求，再把 terminationGracePeriodSeconds 调大'
    const excerpt = searchExcerpt(subtitle, ['terminationgraceperiodseconds'])
    expect(subtitle.endsWith(excerpt.slice(1))).toBe(true)
    // 命中词前保留 6 个字符的上下文，起点不在拉丁词中间时按字截取。
    expect(excerpt).toBe('…请求，再把 terminationGracePeriodSeconds 调大')
    expect(searchExcerpt('一二三四五六七八九十甲乙丙丁戊己庚辛命中', ['命中'])).toBe('…丙丁戊己庚辛命中')

    // 起点落在拉丁词中间：从该词之后的空格开始，不显示半个词；起点是空格时不留前导空白。
    expect(searchExcerpt('Pods drain connections before shutdown and terminationGracePeriodSeconds', ['termination']))
      .toBe('…and terminationGracePeriodSeconds')
    expect(searchExcerpt('A long preamble goes here, abcd terminationGracePeriodSeconds', ['termination']))
      .toBe('…abcd terminationGracePeriodSeconds')
    // 命中已在开头附近或没有命中：原样返回。
    expect(searchExcerpt('main 分支', ['main'])).toBe('main 分支')
    expect(searchExcerpt('没有命中的副标题', ['parser'])).toBe('没有命中的副标题')
  })

  it('never starts the excerpt in the middle of a surrogate pair', () => {
    // 命中位于第 21 个 UTF-16 单元，回退 6 个单元落在第 8 个 emoji 的低代理上：向前退一格保持字符完整。
    expect(searchExcerpt(`${'😀'.repeat(10)}ahit`, ['hit'])).toBe(`…${'😀'.repeat(3)}ahit`)
  })
})
