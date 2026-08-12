package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 持久化到 ToolInvocation 上的 durable Tool approval 状态。
 *
 * <p>精确的不变量：
 *
 * <ul>
 *   <li>{@code required=false}：所有其他字段都为 null。
 *   <li>{@code required=true} 且 undecided：{@code requestedAt} 存在，{@code reason} 可选， 决策相关事实缺失。
 *   <li>{@code required=true} 且 decided：decision、{@code decisionId}、{@code actor}、 {@code
 *       requestedAt} 与 {@code decidedAt} 均存在，{@code decidedAt >= requestedAt}， {@code reason} 可选。
 * </ul>
 */
public record ToolApproval(
    boolean required,
    ToolApprovalDecision decision,
    UUID decisionId,
    String actor,
    String reason,
    Instant requestedAt,
    Instant decidedAt) {

  public ToolApproval {
    if (!required) {
      if (decision != null
          || decisionId != null
          || actor != null
          || reason != null
          || requestedAt != null
          || decidedAt != null) {
        throw new IllegalArgumentException("non-required approval must not carry decision facts");
      }
    } else if (decision == null) {
      if (decisionId != null || actor != null || decidedAt != null) {
        throw new IllegalArgumentException(
            "undecided approval must not carry decisionId, actor or decidedAt");
      }
      requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    } else {
      Objects.requireNonNull(decisionId, "decisionId");
      actor = requireCanonical(actor, "actor");
      requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
      decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
      if (decidedAt.isBefore(requestedAt)) {
        throw new IllegalArgumentException("decidedAt must not precede requestedAt");
      }
    }
    if (reason != null) {
      reason = requireCanonical(reason, "reason", 1024);
    }
  }

  /** 表示该 approval 必需但尚未被决定。 */
  public boolean isUndecided() {
    return required && decision == null;
  }

  /** not-required approval 的最小工厂方法（所有其他字段均为 null）。 */
  public static ToolApproval notRequired() {
    return new ToolApproval(false, null, null, null, null, null, null);
  }

  /** 携带给定 request time 与 reason 的 required undecided approval 的最小工厂方法。 */
  public static ToolApproval request(Instant requestedAt, String reason) {
    return new ToolApproval(true, null, null, null, reason, requestedAt, null);
  }

  /**
   * 对该 approval 应用一个决策（保留 request time）。
   *
   * <p>undecided approval 会变为由给定 {@code decidedAt} 决定的已决状态。已决定的 approval 接受 幂等 replay：若 {@code
   * decisionId}、{@code decision}、{@code actor} 与 {@code reason} 完全相同， 则原样返回 stored approval ——客户端
   * retry 不携带 {@code decidedAt}，因此服务端现场生成的 {@code decidedAt} 应当被忽略。相同的 {@code decisionId} 携带不同
   * payload，或任何不同的 {@code decisionId}，都视为冲突。
   */
  public ToolApproval decide(
      ToolApprovalDecision decision,
      UUID decisionId,
      String actor,
      String reason,
      Instant decidedAt) {
    Objects.requireNonNull(decision, "decision");
    if (!required) {
      throw new IllegalArgumentException("a non-required approval cannot be decided");
    }
    if (this.decision == null) {
      return new ToolApproval(true, decision, decisionId, actor, reason, requestedAt, decidedAt);
    }
    if (this.decision == decision
        && Objects.equals(this.decisionId, decisionId)
        && Objects.equals(this.actor, actor)
        && Objects.equals(this.reason, reason)) {
      return this;
    }
    throw new IllegalArgumentException(
        "approval decision conflict: decisionId "
            + decisionId
            + " does not replay the stored decision");
  }

  private static String requireCanonical(String value, String field) {
    return requireCanonical(value, field, 128);
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be <= " + maxLength + " characters");
    }
    return value;
  }
}
