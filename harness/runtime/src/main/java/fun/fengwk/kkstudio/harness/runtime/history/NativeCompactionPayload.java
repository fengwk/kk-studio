package fun.fengwk.kkstudio.harness.runtime.history;

/**
 * Driver-reported immutable native compaction audit. Native IDs are opaque audit values, never
 * Studio Entry references; this fact neither rewrites history nor grants execution authority.
 */
public record NativeCompactionPayload(
    String trigger,
    Long preTokens,
    Long postTokens,
    Long cumulativeDroppedTokens,
    Long durationMs,
    String nativeHeadId,
    String nativeAnchorId,
    String nativeTailId)
    implements EntryPayload {

  public NativeCompactionPayload {
    text(trigger, 64, false);
    text(nativeHeadId, 256, true);
    text(nativeAnchorId, 256, true);
    text(nativeTailId, 256, true);
    count(preTokens);
    count(postTokens);
    count(cumulativeDroppedTokens);
    count(durationMs);
  }

  private static void text(String value, int limit, boolean nullable) {
    if (value == null && nullable) {
      return;
    }
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > limit) {
      throw new IllegalArgumentException("invalid native compaction text");
    }
  }

  private static void count(Long value) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException("native compaction counts must be nonnegative");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.NATIVE_COMPACTION;
  }
}
