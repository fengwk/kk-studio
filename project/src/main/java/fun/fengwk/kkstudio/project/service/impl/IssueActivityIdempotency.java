package fun.fengwk.kkstudio.project.service.impl;

import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 业务活动的请求键与规范化请求指纹（设计 §4.6）：一个动作一条活动，活动身份为 {@code kind:requestKey}，指纹由动作与规范化请求字段决定。
 *
 * <p>判定语义：无同键活动表示首次执行；同键同指纹是丢响应后的精确重试，调用方必须返回原结果而不重复应用、不重复接受 Run 或 Harness 指令；同键异指纹是请求键复用，
 * 确定性冲突（HTTP 409），绝不按新请求继续执行。
 */
final class IssueActivityIdempotency {

  private static final char FIELD_SEPARATOR = '\u001f';

  private IssueActivityIdempotency() {}

  /** 一条业务活动的身份：{@code key} 是活动请求键，{@code requestHash} 是规范化请求指纹。 */
  record Identity(IssueActivityKind kind, String key, String requestHash) {

    Identity {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(requestHash, "requestHash");
    }
  }

  /**
   * 构造一次业务动作的活动身份：{@code action} 与全部请求字段按固定分隔符拼接后取 SHA-256，字段顺序即协议顺序。
   *
   * <p>指纹含动作名，因此同一请求键被另一个动作复用时指纹必然不同，从而在写入前就确定性冲突。
   */
  static Identity identity(
      IssueActivityKind kind, String action, String requestKey, Object... fields) {
    StringBuilder canonical = new StringBuilder(action);
    for (Object field : fields) {
      canonical.append(FIELD_SEPARATOR).append(field == null ? "" : field);
    }
    return new Identity(
        kind,
        kind.name().toLowerCase(Locale.ROOT) + ":" + requestKey,
        ProjectValidationUtils.sha256(canonical.toString()));
  }

  /**
   * 返回该身份已提交的活动：{@code null} 表示首次执行；同键同指纹返回原活动供调用方精确重放；同键异指纹抛冲突。
   *
   * <p>调用方必须在任何版本校验、状态/额度校验与外部派发之前调用本方法。
   */
  static IssueActivity findApplied(
      IssueActivityRepository repository, UUID issueId, Identity identity) {
    IssueActivity existing = repository.findByIdempotencyKey(issueId, identity.key());
    if (existing == null) {
      return null;
    }
    if (!identity.requestHash().equals(existing.getRequestHash())) {
      throw new ProjectDuplicateException(
          "issue_activity", "Request key was reused with a different request payload");
    }
    return existing;
  }
}
