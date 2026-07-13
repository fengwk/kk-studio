package fun.fengwk.kkstudio.harness.runtime.session;

/** Append 的确定性结果；冲突不会留下可见孤儿节点。 */
public record AppendResult(boolean appended, SessionEntry entry) {
  public static AppendResult conflict() {
    return new AppendResult(false, null);
  }

  public static AppendResult appended(SessionEntry entry) {
    return new AppendResult(true, entry);
  }
}
