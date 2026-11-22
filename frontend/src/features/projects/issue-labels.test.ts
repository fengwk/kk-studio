import { afterEach, describe, expect, it } from 'vitest'
import { ISSUE_ACTIVITY_KIND_VALUES, ISSUE_RUN_STATUS_VALUES } from '@/shared/api/generated/contracts'
import { setLocale, translate } from '@/shared/i18n'
import {
  ACTIVITY_KIND_LABEL_KEY,
  RUN_STATUS_LABEL_KEY,
  readableStateNameOf,
  stateNameOf,
  toStateNames,
} from './issue-labels'

afterEach(() => {
  // 语言是模块级状态，避免污染其他用例。
  setLocale('zh-CN')
})

describe('issue-labels', () => {
  it('按工作流声明解析阶段名称，未声明的编码回退为编码本身', () => {
    // 历史 Issue 可能停在工作流已删除的阶段：此时仍要显示可读的原始编码，而不是空白。
    const stateNames = toStateNames([
      { state: 'INIT', name: '待开始' },
      { state: 'WORK', name: '处理中' },
    ])

    expect(stateNameOf(stateNames, 'WORK')).toBe('处理中')
    expect(stateNameOf(stateNames, 'LEGACY_REVIEW')).toBe('LEGACY_REVIEW')
    // 快照未到达（没有工作流）时同样是编码回退。
    expect(stateNameOf(toStateNames(undefined), 'INIT')).toBe('INIT')
  })

  it('面向说明文案的阶段名称只返回工作流声明的可读名称', () => {
    // 说明文案不能露出内部编码：名称缺失或与编码相同时退回通用说法。
    const stateNames = toStateNames([
      { state: 'INIT', name: '待开始' },
      { state: 'WORK', name: 'WORK' },
    ])

    expect(readableStateNameOf(stateNames, 'INIT')).toBe('待开始')
    expect(readableStateNameOf(stateNames, 'WORK')).toBeNull()
    expect(readableStateNameOf(stateNames, 'LEGACY_REVIEW')).toBeNull()
  })

  it('每个 Run 状态都有中英文文案，不会退化成缺失占位符', () => {
    // Run 状态映射必须覆盖后端全部状态，否则界面会露出内部枚举码。
    for (const status of ISSUE_RUN_STATUS_VALUES) {
      const key = RUN_STATUS_LABEL_KEY[status]
      const zh = translate(key)
      setLocale('en-US')
      const en = translate(key)
      setLocale('zh-CN')

      expect(zh).not.toContain('⟦missing')
      expect(en).not.toContain('⟦missing')
      expect(zh).not.toBe(en)
    }
  })

  it('每种活动类型都有中英文文案，不以原始枚举码充当标签', () => {
    // 活动类型同样来自enum：漏一个就会把 COMMENT/SPEC_CHANGE 这样的码直接摊在时间线上。
    for (const kind of ISSUE_ACTIVITY_KIND_VALUES) {
      const key = ACTIVITY_KIND_LABEL_KEY[kind]
      const zh = translate(key)
      setLocale('en-US')
      const en = translate(key)
      setLocale('zh-CN')

      expect(zh).not.toContain('⟦missing')
      expect(en).not.toContain('⟦missing')
      expect(zh).not.toBe(kind)
      expect(en).not.toBe(kind)
    }
  })
})
