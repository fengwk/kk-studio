package fun.fengwk.kkstudio.harness.runtime.entry;

/** 不修改旧 Entry 的压缩摘要及其保留边界。 */
public record CompactionEntryPayload(
    String summary, long firstKeptEntryId, int tokensBefore, String detailsJson)
    implements RuntimeEntryPayload {

  public CompactionEntryPayload {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
    if (firstKeptEntryId <= 0) {
      throw new IllegalArgumentException("firstKeptEntryId must be positive");
    }
    if (tokensBefore < 0) {
      throw new IllegalArgumentException("tokensBefore must not be negative");
    }
    if (detailsJson == null || detailsJson.isBlank()) {
      throw new IllegalArgumentException("detailsJson must not be blank");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.COMPACTION;
  }
}
