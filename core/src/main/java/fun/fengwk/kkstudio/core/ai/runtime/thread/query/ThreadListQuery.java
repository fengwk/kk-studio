package fun.fengwk.kkstudio.core.ai.runtime.thread.query;

/** Validated Thread keyset list request. */
public record ThreadListQuery(ThreadSort sort, ThreadCursor cursor, int limit) {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public static ThreadListQuery parse(String rawSort, String rawCursor, Integer rawLimit) {
    ThreadSort sort = ThreadSort.parse(rawSort);
    int limit = rawLimit == null ? DEFAULT_LIMIT : rawLimit;
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    return new ThreadListQuery(sort, ThreadCursorCodec.decode(rawCursor, sort), limit);
  }
}
