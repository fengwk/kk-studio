package fun.fengwk.kkstudio.harness.runtime.input;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一份已校验并规范化的人工输入答案：按问题位置对应的答案列表，或明确的拒答。
 *
 * <p>答案本身是工具结果：{@code {"answers":[["1080P"],["成片","字幕"]]}} 或 {@code {"declined":true}}。归一化保证仅选择顺序不同
 * 的提交比较相等：去重、去首尾空白，并按选项 label 的问卷顺序排列（自定义文本固定最后）。拒答是明确的成功结果，绝不虚构默认答案。
 */
public record HumanInputAnswers(boolean declined, List<List<String>> answers) {

  /** 单个回答文本的字符上限。 */
  public static final int MAX_ANSWER_CHARACTERS = 4096;

  /** 单次提交全部回答文本的字符上限，保证规范化答案作为工具结果始终小于 ToolResult 的 details 上限。 */
  public static final int MAX_TOTAL_ANSWER_CHARACTERS = 64 * 1024;

  public HumanInputAnswers {
    Objects.requireNonNull(answers, "answers");
    List<List<String>> copied = new ArrayList<>(answers.size());
    for (List<String> questionAnswers : answers) {
      Objects.requireNonNull(questionAnswers, "answers[]");
      List<String> entries = new ArrayList<>(questionAnswers.size());
      for (String answer : questionAnswers) {
        entries.add(HumanInputTexts.requireText(answer, "answer", MAX_ANSWER_CHARACTERS));
      }
      if (entries.isEmpty()) {
        throw new IllegalArgumentException("each question requires at least one answer");
      }
      copied.add(List.copyOf(entries));
    }
    answers = List.copyOf(copied);
    if (declined && !answers.isEmpty()) {
      throw new IllegalArgumentException("a declined submission must not carry answers");
    }
    if (!declined && answers.isEmpty()) {
      throw new IllegalArgumentException("an accepted submission must carry answers");
    }
  }

  /**
   * 按冻结问卷校验并规范化一次提交。
   *
   * <p>规则：拒答不接受任何答案；否则答案必须覆盖全部问题且顺序按问题位置对应。单选恰好一个回答；多选至少一个回答、去重后至多一项 自定义文本（非选项
   * label）。每题都可填写自定义回答（含无选项的问题），因此不凭空补默认选项、不解释普通消息。
   *
   * @param questionnaire 冻结问卷
   * @param declined 是否为明确拒答
   * @param submitted 原始答案（按问题位置对应，可为 null 表示未填写）
   * @return 规范化答案，可直接作为工具结果
   * @throws IllegalArgumentException 提交不满足冻结问卷
   */
  public static HumanInputAnswers accept(
      HumanInputQuestionnaire questionnaire, boolean declined, List<List<String>> submitted) {
    Objects.requireNonNull(questionnaire, "questionnaire");
    if (declined) {
      if (submitted != null && !submitted.isEmpty()) {
        throw new IllegalArgumentException("a declined submission must not carry answers");
      }
      return new HumanInputAnswers(true, List.of());
    }
    if (submitted == null) {
      throw new IllegalArgumentException("a submission must carry answers for every question");
    }
    if (submitted.size() != questionnaire.questions().size()) {
      throw new IllegalArgumentException(
          "a submission must answer every question exactly once: expected "
              + questionnaire.questions().size()
              + " got "
              + submitted.size());
    }
    List<List<String>> normalized = new ArrayList<>(submitted.size());
    int totalCharacters = 0;
    for (int index = 0; index < submitted.size(); index++) {
      List<String> entries =
          normalizeAnswer(index, questionnaire.questions().get(index), submitted.get(index));
      for (String entry : entries) {
        totalCharacters += entry.length();
      }
      normalized.add(entries);
    }
    if (totalCharacters > MAX_TOTAL_ANSWER_CHARACTERS) {
      throw new IllegalArgumentException(
          "a submission must not exceed " + MAX_TOTAL_ANSWER_CHARACTERS + " characters");
    }
    return new HumanInputAnswers(false, normalized);
  }

  /**
   * 校验并规范化单个问题的答案。
   *
   * <p>拒绝信息只使用问题下标与原因，绝不回显问题文本或答案内容：提交者可能提交敏感值。
   */
  private static List<String> normalizeAnswer(
      int questionIndex, HumanInputQuestion question, List<String> submitted) {
    String subject = "question#" + questionIndex;
    if (submitted == null || submitted.isEmpty()) {
      throw new IllegalArgumentException(subject + " requires an answer");
    }
    Set<String> deduped = new LinkedHashSet<>();
    for (String answer : submitted) {
      if (answer == null) {
        throw new IllegalArgumentException(subject + " requires a non-null answer");
      }
      String normalized = answer.strip();
      if (normalized.isEmpty()) {
        throw new IllegalArgumentException(subject + " requires a non-blank answer");
      }
      if (normalized.length() > MAX_ANSWER_CHARACTERS) {
        throw new IllegalArgumentException(
            "an answer must be <= " + MAX_ANSWER_CHARACTERS + " characters");
      }
      deduped.add(normalized);
    }
    if (!question.multiple() && deduped.size() != 1) {
      throw new IllegalArgumentException(subject + " allows exactly one answer");
    }
    List<Integer> selectedIndexes = new ArrayList<>();
    String custom = null;
    for (String answer : deduped) {
      int optionIndex = question.optionIndex(answer);
      if (optionIndex >= 0) {
        selectedIndexes.add(optionIndex);
      } else {
        if (custom != null) {
          throw new IllegalArgumentException(subject + " allows at most one custom answer");
        }
        custom = answer;
      }
    }
    selectedIndexes.sort(Integer::compare);
    List<String> selected = new ArrayList<>(selectedIndexes.size() + 1);
    for (int optionIndex : selectedIndexes) {
      selected.add(question.options().get(optionIndex).label());
    }
    if (custom != null) {
      selected.add(custom);
    }
    return List.copyOf(selected);
  }
}
