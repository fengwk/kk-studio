package fun.fengwk.kkstudio.harness.runtime.input;

import java.util.List;
import java.util.Objects;

/**
 * 一次 {@code ask_user} 调用的冻结问卷：接受后不再变化，答案严格按问题位置对应。
 *
 * <p>模型不生成 questionId / optionId，也不定义条件显隐、脚本或跨问卷引用；问卷只描述人类可回答的问题集合。
 */
public record HumanInputQuestionnaire(List<HumanInputQuestion> questions) {

  /** 单份问卷的问题数上限。 */
  public static final int MAX_QUESTIONS = 20;

  public HumanInputQuestionnaire {
    questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
    if (questions.isEmpty()) {
      throw new IllegalArgumentException("a questionnaire must declare at least one question");
    }
    if (questions.size() > MAX_QUESTIONS) {
      throw new IllegalArgumentException(
          "a questionnaire must not declare more than " + MAX_QUESTIONS + " questions");
    }
  }
}
