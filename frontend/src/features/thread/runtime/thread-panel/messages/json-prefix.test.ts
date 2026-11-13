import { describe, expect, it } from 'vitest'
import { parseJsonPrefix } from '@/features/thread/runtime/thread-panel/messages/json-prefix'

/** 流式参数只有前缀可用；解析必须在任何截断位置都给出已表达的最长合理值。 */
describe('parseJsonPrefix', () => {
  it('parses complete JSON natively and reports completeness', () => {
    const parsed = parseJsonPrefix('{"path":"App.java","edits":[{"old_string":"a\\nb","new_string":"c"}]}')

    expect(parsed.complete).toBe(true)
    expect(parsed.value).toEqual({
      path: 'App.java',
      edits: [{ old_string: 'a\nb', new_string: 'c' }],
    })
  })

  it('treats complete non-object JSON as complete', () => {
    expect(parseJsonPrefix('[]')).toEqual({ complete: true, value: [] })
    expect(parseJsonPrefix('"text"')).toEqual({ complete: true, value: 'text' })
    expect(parseJsonPrefix('42')).toEqual({ complete: true, value: 42 })
    expect(parseJsonPrefix('null')).toEqual({ complete: true, value: null })
  })

  it('yields the text received so far for an unterminated string', () => {
    const parsed = parseJsonPrefix('{"path":"App.java","edits":[{"old_string":"alpha\\nbeta')

    expect(parsed.complete).toBe(false)
    expect(parsed.value).toEqual({ path: 'App.java', edits: [{ old_string: 'alpha\nbeta' }] })
  })

  it('drops an incomplete escape at the end of an unterminated string', () => {
    // 半个转义序列不是正文：丢掉它，避免把 "\\" 或 "\\u12" 显示成用户看不到的字符。
    expect(parseJsonPrefix('{"content":"abc\\')).toEqual({
      complete: false,
      value: { content: 'abc' },
    })
    expect(parseJsonPrefix('{"content":"abc\\u12')).toEqual({
      complete: false,
      value: { content: 'abc' },
    })
  })

  it('decodes complete unicode escapes that arrived before the cut', () => {
    const parsed = parseJsonPrefix('{"content":"\\u4e2d\\u6587 and more')

    expect(parsed.value).toEqual({ content: '中文 and more' })
  })

  it('decodes every JSON escape form inside a truncated string', () => {
    const parsed = parseJsonPrefix(String.raw`{"content":"a\"b\\c\/d\be\ff\tg\rh`)

    expect(parsed.value).toEqual({ content: 'a"b\\c/d\be\ff\tg\rh' })
  })

  it('keeps parsed members of an unterminated object and omits a key without value', () => {
    expect(parseJsonPrefix('{"path":"App.java","')).toEqual({
      complete: false,
      value: { path: 'App.java' },
    })
    expect(parseJsonPrefix('{"path":"App.java","content":')).toEqual({
      complete: false,
      value: { path: 'App.java' },
    })
    // 键名本身未闭合时整条成员都不成立。
    expect(parseJsonPrefix('{"path":"App.java","conte')).toEqual({
      complete: false,
      value: { path: 'App.java' },
    })
  })

  it('keeps parsed entries of an unterminated nested array of objects', () => {
    const raw = '{"path":"App.java","edits":['
      + '{"old_string":"one","new_string":"ONE","replace_all":true},'
      + '{"old_string":"two","new_string":"TWO"},'
      + '{"old_string":"three","new_str'

    expect(parseJsonPrefix(raw)).toEqual({
      complete: false,
      value: {
        path: 'App.java',
        edits: [
          { old_string: 'one', new_string: 'ONE', replace_all: true },
          { old_string: 'two', new_string: 'TWO' },
          { old_string: 'three' },
        ],
      },
    })
  })

  it('omits an incomplete number or literal at the end of the text', () => {
    // 数字与字面量可能继续增长（"1" -> "12"、"tru" -> "true"），未确认前不展示。
    expect(parseJsonPrefix('{"limit":12,"offset":')).toEqual({
      complete: false,
      value: { limit: 12 },
    })
    expect(parseJsonPrefix('{"offset":3')).toEqual({ complete: false, value: {} })
    expect(parseJsonPrefix('{"flag":tru')).toEqual({ complete: false, value: {} })
    expect(parseJsonPrefix('{"literal":true')).toEqual({ complete: false, value: {} })
    // 后面还有字节时取值已确定。
    expect(parseJsonPrefix('{"literal":true,"other":1')).toEqual({
      complete: false,
      value: { literal: true },
    })
  })

  it('parses a streamed grep call that carries glob instead of the removed include', () => {
    const raw = '{"pattern":"needle","path":"/repo","glob":"**/*.ts","ignore_case":true,"lim'

    expect(parseJsonPrefix(raw)).toEqual({
      complete: false,
      value: { pattern: 'needle', path: '/repo', glob: '**/*.ts', ignore_case: true },
    })
  })

  it('returns an empty prefix for blank or non-JSON text', () => {
    expect(parseJsonPrefix('')).toEqual({ complete: false, value: undefined })
    expect(parseJsonPrefix('   ')).toEqual({ complete: false, value: undefined })
    expect(parseJsonPrefix('not-json {')).toEqual({ complete: false, value: undefined })
  })
})
