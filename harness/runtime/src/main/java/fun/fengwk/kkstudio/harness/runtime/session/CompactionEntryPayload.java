package fun.fengwk.kkstudio.harness.runtime.session;

/** 不修改旧 Entry 的压缩摘要及其保留边界。 */
public record CompactionEntryPayload(
    String summary, String firstKeptEntryId, int tokensBefore, String detailsJson)
    implements SessionEntryPayload {
  public CompactionEntryPayload {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
    if (firstKeptEntryId == null || firstKeptEntryId.isBlank()) {
      throw new IllegalArgumentException("firstKeptEntryId must not be blank");
    }
    if (tokensBefore < 0) {
      throw new IllegalArgumentException("tokensBefore must not be negative");
    }
    if (detailsJson == null || detailsJson.isBlank()) {
      throw new IllegalArgumentException("detailsJson must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.COMPACTION;
  }
}
