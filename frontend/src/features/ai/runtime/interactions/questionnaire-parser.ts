import type {
  AskUserOption,
  AskUserQuestion,
  AskUserQuestionnaire,
  QuestionDraft,
} from './questionnaire-types'

/**
 * 健壮解析 JSON 中的问卷定义。
 */
export function parseQuestionnaire(argumentsJson: string | null | undefined): AskUserQuestionnaire | null {
  if (!argumentsJson || typeof argumentsJson !== 'string') {
    return null
  }
  try {
    const parsed = JSON.parse(argumentsJson)
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.questions)) {
      return null
    }

    const questions: AskUserQuestion[] = []
    for (const rawQ of parsed.questions) {
      if (!rawQ || typeof rawQ !== 'object' || typeof rawQ.question !== 'string') {
        continue
      }

      const options: AskUserOption[] = []
      if (Array.isArray(rawQ.options)) {
        for (const rawOpt of rawQ.options) {
          if (rawOpt && typeof rawOpt === 'object' && typeof rawOpt.label === 'string' && rawOpt.label.trim()) {
            options.push({
              label: rawOpt.label.trim(),
              description: typeof rawOpt.description === 'string' ? rawOpt.description.trim() : undefined,
              recommended: rawOpt.recommended === true,
            })
          }
        }
      }

      questions.push({
        question: rawQ.question.trim(),
        multiple: rawQ.multiple === true,
        options,
      })
    }

    if (questions.length === 0) {
      return null
    }

    return { questions }
  } catch {
    return null
  }
}

/**
 * 解析已完成的 ToolResult（已物化答案或明确拒答）。
 */
export function parseCompletedResult(resultJson: string | null | undefined): {
  declined: boolean
  answers?: string[][]
} | null {
  if (!resultJson || typeof resultJson !== 'string') {
    return null
  }
  try {
    const parsed = JSON.parse(resultJson)
    if (!parsed || typeof parsed !== 'object') {
      return null
    }
    if (parsed.declined === true) {
      return { declined: true }
    }
    if (Array.isArray(parsed.answers)) {
      const answers: string[][] = parsed.answers.map((row: unknown) =>
        Array.isArray(row) ? row.filter((item): item is string => typeof item === 'string') : [],
      )
      return { declined: false, answers }
    }
    return null
  } catch {
    return null
  }
}

/**
 * 按冻结问卷将用户当前草稿校验并规范化为答案数组：string[][]。
 * 如果有任何一题尚未作答，或者答案非法，返回 null（提交禁用）。
 */
export function normalizeAnswers(
  questionnaire: AskUserQuestionnaire,
  drafts: Record<number, QuestionDraft | undefined>,
): string[][] | null {
  if (!questionnaire || questionnaire.questions.length === 0) {
    return null
  }

  const results: string[][] = []

  for (let i = 0; i < questionnaire.questions.length; i++) {
    const question = questionnaire.questions[i]
    const draft = drafts[i]
    if (!draft) {
      return null
    }

    const custom = draft.customText ? draft.customText.trim() : ''
    const selected = (draft.selectedLabels || [])
      .map((s) => s.trim())
      .filter((s) => s.length > 0)

    const questionAnswers: string[] = []

    if (!question.multiple) {
      // 单选：单选为默认；优先采用选中的选项，若未选则采用非空自定义输入；恰好一个回答。
      if (selected.length === 1) {
        questionAnswers.push(selected[0])
      } else if (custom) {
        questionAnswers.push(custom)
      } else {
        return null
      }
    } else {
      // 多选：支持选择若干预置选项，且可额外有一项自定义文本；去重。
      const set = new Set<string>()
      for (const item of selected) {
        set.add(item)
      }
      if (custom && !set.has(custom)) {
        set.add(custom)
      }
      if (set.size === 0) {
        return null
      }
      questionAnswers.push(...Array.from(set))
    }

    results.push(questionAnswers)
  }

  return results
}
