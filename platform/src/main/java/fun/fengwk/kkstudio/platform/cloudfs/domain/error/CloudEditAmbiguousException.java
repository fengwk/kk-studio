package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 文本精确编辑时，在 replace_all 为 false 的情况下匹配到了多处相同的 old_string。 */
public class CloudEditAmbiguousException extends CloudFileSystemException {

  private final CloudPath path;
  private final int matchCount;

  public CloudEditAmbiguousException(CloudPath path, int matchCount) {
    super(
        String.format("Found %d exact matches in %s, but replace_all is false", matchCount, path));
    this.path = path;
    this.matchCount = matchCount;
  }

  public CloudPath getPath() {
    return path;
  }

  public int getMatchCount() {
    return matchCount;
  }
}
