package fun.fengwk.kkstudio.harness.runtime.join;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次源命令接受后的不可变 join 凭据。匹配版本和结果 head 只能同时冻结一次；父交付引用其 command sequence，不能从后续的子 Thread head 重新推导旧结果。
 */
public record ThreadJoin(
    UUID invocationId,
    String requestHash,
    UUID parentThreadId,
    UUID childThreadId,
    long sourceCommandSequence,
    long afterVersion,
    String agent,
    Integer maxTurns,
    long reminderTurn,
    Long matchedIdleVersion,
    UUID resultHeadEntryId,
    Long deliveryCommandSequence,
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public ThreadJoin {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (requestHash == null || !HASH.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 hex string");
    }
    if (parentThreadId != null && parentThreadId.equals(childThreadId)) {
      throw new IllegalArgumentException("join parent and child must differ");
    }
    if (sourceCommandSequence <= 0 || afterVersion < 0 || reminderTurn < 0) {
      throw new IllegalArgumentException("invalid join command/version/reminder");
    }
    if (agent == null || agent.isBlank() || agent.length() > 256) {
      throw new IllegalArgumentException("agent must be nonblank and at most 256 characters");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    if ((matchedIdleVersion == null) != (resultHeadEntryId == null)
        || (matchedIdleVersion != null && matchedIdleVersion <= afterVersion)) {
      throw new IllegalArgumentException(
          "matched receipt must be complete and newer than acceptance");
    }
    if (deliveryCommandSequence != null
        && (parentThreadId == null || matchedIdleVersion == null || deliveryCommandSequence <= 0)) {
      throw new IllegalArgumentException("delivery requires a matched parent join");
    }
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt precedes createdAt");
    }
  }

  public boolean matched() {
    return matchedIdleVersion != null;
  }

  public ThreadJoin match(long idleVersion, UUID resultHead, Instant now) {
    if (matched() || idleVersion <= afterVersion) {
      throw new IllegalArgumentException("join already matched or idle not after acceptance");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        afterVersion,
        agent,
        maxTurns,
        reminderTurn,
        idleVersion,
        Objects.requireNonNull(resultHead, "resultHead"),
        null,
        createdAt,
        now);
  }

  public ThreadJoin delivered(long sequence, Instant now) {
    if (!matched() || deliveryCommandSequence != null) {
      throw new IllegalArgumentException("join not pending delivery");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        afterVersion,
        agent,
        maxTurns,
        reminderTurn,
        matchedIdleVersion,
        resultHeadEntryId,
        sequence,
        createdAt,
        now);
  }

  public ThreadJoin remind(long turn, Instant now) {
    if (matched() || turn <= reminderTurn) {
      throw new IllegalArgumentException("reminder must advance an unmatched join");
    }
    return new ThreadJoin(
        invocationId,
        requestHash,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        afterVersion,
        agent,
        maxTurns,
        turn,
        null,
        null,
        null,
        createdAt,
        now);
  }

  public static void validateTransition(ThreadJoin old, ThreadJoin next) {
    Objects.requireNonNull(old, "old");
    Objects.requireNonNull(next, "next");
    if (!old.invocationId.equals(next.invocationId)
        || !old.requestHash.equals(next.requestHash)
        || !Objects.equals(old.parentThreadId, next.parentThreadId)
        || !old.childThreadId.equals(next.childThreadId)
        || old.sourceCommandSequence != next.sourceCommandSequence
        || old.afterVersion != next.afterVersion
        || !old.agent.equals(next.agent)
        || !Objects.equals(old.maxTurns, next.maxTurns)
        || !old.createdAt.equals(next.createdAt)
        || next.updatedAt.isBefore(old.updatedAt)
        || next.reminderTurn < old.reminderTurn
        || (old.matched()
            && (!Objects.equals(old.matchedIdleVersion, next.matchedIdleVersion)
                || !Objects.equals(old.resultHeadEntryId, next.resultHeadEntryId)))
        || (old.deliveryCommandSequence != null
            && !old.deliveryCommandSequence.equals(next.deliveryCommandSequence))
        || (old.matched() && next.reminderTurn != old.reminderTurn)) {
      throw new IllegalArgumentException("join durable identity or receipt cannot change");
    }
  }
}
