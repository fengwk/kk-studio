package fun.fengwk.kkstudio.harness.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的人工输入提交：拥有该 invocation 的明确 Thread、目标 Tool invocation、客户端生成的稳定 {@code submissionId}、
 * 操作用户，以及按问题位置对应的原始答案或明确拒答。
 *
 * <p>答案不在此处做问卷校验：只有运行时在锁定真实调用并读取冻结问卷后才能判断覆盖、单选/多选与自定义回答是否合法。服务端从其 Clock 推导 {@code
 * acceptedAt}，因此重试永不携带客户端时间戳；相同 {@code submissionId} 的重试必须携带相同答案才会被接受为原回执。
 */
public record ToolInputSubmissionCommand(
    UUID threadId,
    UUID toolInvocationId,
    UUID submissionId,
    String actor,
    boolean declined,
    List<List<String>> answers) {

  public ToolInputSubmissionCommand {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(toolInvocationId, "toolInvocationId");
    Objects.requireNonNull(submissionId, "submissionId");
    actor = requireCanonicalActor(actor);
    Objects.requireNonNull(answers, "answers");
    List<List<String>> copied = new ArrayList<>(answers.size());
    for (List<String> questionAnswers : answers) {
      // null 表示该问题未填写，交由 runtimes 按冻结问卷给出精确拒绝，因此不能用 List.copyOf 提前抹平。
      copied.add(questionAnswers == null ? null : List.copyOf(questionAnswers));
    }
    answers = Collections.unmodifiableList(copied);
    if (declined && !answers.isEmpty()) {
      throw new IllegalArgumentException("a declined submission must not carry answers");
    }
  }

  private static String requireCanonicalActor(String actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.isBlank()) {
      throw new IllegalArgumentException("actor must not be blank");
    }
    if (!actor.equals(actor.strip())) {
      throw new IllegalArgumentException("actor must not contain surrounding whitespace");
    }
    if (actor.length() > 128) {
      throw new IllegalArgumentException("actor must be <= 128 characters");
    }
    return actor;
  }
}
