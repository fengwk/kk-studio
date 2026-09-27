import { describe, expect, it } from 'vitest'
import {
  normalizeAnswers,
  parseCompletedResult,
  parseQuestionnaire,
} from './questionnaire-parser'

describe('questionnaire-parser', () => {
  const sampleJson = JSON.stringify({
    questions: [
      {
        question: '最终视频使用什么分辨率？',
        options: [
          { label: '1080P', description: '清晰度与体积平衡', recommended: true },
          { label: '720P', description: '用于预览' },
        ],
      },
      {
        question: '需要哪些交付物？',
        multiple: true,
        options: [{ label: '成片' }, { label: '字幕' }],
      },
      {
        question: '有什么额外需求？',
        options: [],
      },
    ],
  })

  it('parses structured questionnaire correctly', () => {
    const q = parseQuestionnaire(sampleJson)
    expect(q).not.toBeNull()
    expect(q?.questions).toHaveLength(3)
    expect(q?.questions[0].question).toBe('最终视频使用什么分辨率？')
    expect(q?.questions[0].multiple).toBe(false)
    expect(q?.questions[0].options?.[0].recommended).toBe(true)
    expect(q?.questions[1].multiple).toBe(true)
    expect(q?.questions[2].options).toEqual([])
  })

  it('normalizes single-choice with option and multiple-choice with options + custom', () => {
    const q = parseQuestionnaire(sampleJson)!
    const drafts = {
      0: { selectedLabels: ['1080P'], customText: '' },
      1: { selectedLabels: ['成片', '字幕'], customText: '另提供工程源文件' },
      2: { selectedLabels: [], customText: '请在周五前完成' },
    }

    const answers = normalizeAnswers(q, drafts)
    expect(answers).toEqual([
      ['1080P'],
      ['成片', '字幕', '另提供工程源文件'],
      ['请在周五前完成'],
    ])
  })

  it('supports single-choice with custom input when no option is chosen', () => {
    const q = parseQuestionnaire(sampleJson)!
    const drafts = {
      0: { selectedLabels: [], customText: '4K超清' },
      1: { selectedLabels: ['成片'], customText: '' },
      2: { selectedLabels: [], customText: '无' },
    }

    const answers = normalizeAnswers(q, drafts)
    expect(answers).toEqual([['4K超清'], ['成片'], ['无']])
  })

  it('returns null if any question is not answered', () => {
    const q = parseQuestionnaire(sampleJson)!
    const drafts = {
      0: { selectedLabels: ['1080P'], customText: '' },
      1: { selectedLabels: [], customText: '' }, // 未选未填
      2: { selectedLabels: [], customText: '无' },
    }

    const answers = normalizeAnswers(q, drafts)
    expect(answers).toBeNull()
  })

  it('parses completed results (declined vs answers)', () => {
    expect(parseCompletedResult('{"declined":true}')).toEqual({ declined: true })
    expect(parseCompletedResult('{"answers":[["1080P"],["成片"]]}')).toEqual({
      declined: false,
      answers: [['1080P'], ['成片']],
    })
    expect(parseCompletedResult(null)).toBeNull()
  })
})
