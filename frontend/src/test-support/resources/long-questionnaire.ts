/**
 * 长问卷 fixture：包含 21 道题、每题 21 个选项以及较长的问题、选项和答案文本。
 *
 * <p>供问卷解析与交互卡片测试复用，验证完整解析与渲染；结构在 fixture 内集中构造，
 * 不把巨型问卷字面量写进测试代码。
 */
export const LONG_QUESTION_COUNT = 21
export const LONG_OPTION_COUNT = 21
export const LONG_ANSWER = 'a'.repeat(5000)

export const LONG_QUESTIONNAIRE = {
  questions: Array.from({ length: LONG_QUESTION_COUNT }, () => ({
    question: 'q'.repeat(1500),
    options: Array.from({ length: LONG_OPTION_COUNT }, (_, i) => ({
      label: `${'l'.repeat(300)}${i}`,
      description: 'd'.repeat(1200),
    })),
  })),
}

export const LONG_QUESTIONNAIRE_JSON = JSON.stringify(LONG_QUESTIONNAIRE)
