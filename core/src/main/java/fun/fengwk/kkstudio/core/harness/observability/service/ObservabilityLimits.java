package fun.fengwk.kkstudio.core.harness.observability.service;

/** Page-size and cursor validators shared by the JSON and SSE endpoints. */
public final class ObservabilityLimits {

  public static final int DEFAULT_LIMIT = 100;
  public static final int MIN_LIMIT = 1;
  public static final int MAX_LIMIT = 200;

  private ObservabilityLimits() {}

  public static int normalizeLimit(int value) {
    if (value < MIN_LIMIT || value > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT + ": " + value);
    }
    return value;
  }

  public static int normalizeLimit(Integer value, int defaultLimit) {
    if (value == null) {
      return defaultLimit;
    }
    return normalizeLimit(value.intValue());
  }

  public static void requireNonNegativeCursor(long cursor, String field) {
    if (cursor < 0) {
      throw new IllegalArgumentException(field + " must be non-negative: " + cursor);
    }
  }

  /** Strict parser for SSE resume cursors that come from a query or {@code Last-Event-ID}. */
  public static long parseOptionalCursor(String value, String field) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    String trimmed = value.trim();
    long parsed;
    try {
      parsed = Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          field + " must be a non-negative long decimal: " + value, error);
    }
    if (parsed < 0) {
      throw new IllegalArgumentException(field + " must be non-negative: " + value);
    }
    return parsed;
  }

  /**
   * Resolve the resume cursor for native EventSource reconnects.
   *
   * <p>The browser keeps the original query cursor on automatic reconnect and additionally supplies
   * {@code Last-Event-ID}. Taking the maximum of both valid non-negative decimals never rewinds
   * progress back to the original query value. Values stay decimal strings until this Java long
   * parse so Root Activity decimal string IDs are not coerced through JavaScript numbers.
   */
  public static long resolveResumeCursor(String queryValue, String lastEventId, String field) {
    long fromQuery = parseOptionalCursor(queryValue, field);
    long fromHeader = parseOptionalCursor(lastEventId, field);
    return Math.max(fromQuery, fromHeader);
  }
}
