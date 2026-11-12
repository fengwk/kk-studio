package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;

import java.util.Objects;

/** Accepted child identity and, for synchronous task calls, the immutable Join result. */
public record SubagentTaskResult(SubagentTaskAcceptance acceptance, ThreadJoinReceipt receipt) {

  public SubagentTaskResult {
    Objects.requireNonNull(acceptance, "acceptance");
  }
}
