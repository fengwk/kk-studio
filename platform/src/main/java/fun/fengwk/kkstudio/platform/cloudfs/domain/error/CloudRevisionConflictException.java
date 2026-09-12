package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 文本 CAS 写入或编辑时 expected_revision 与实际 current revision 不一致时抛出。 */
public class CloudRevisionConflictException extends CloudFileSystemException {

  private final CloudPath path;
  private final long currentRevision;
  private final long expectedRevision;

  public CloudRevisionConflictException(
      CloudPath path, long currentRevision, long expectedRevision) {
    super(
        String.format(
            "Text revision conflict at %s: current revision is %d, expected %d",
            path, currentRevision, expectedRevision));
    this.path = path;
    this.currentRevision = currentRevision;
    this.expectedRevision = expectedRevision;
  }

  public CloudPath getPath() {
    return path;
  }

  public long getCurrentRevision() {
    return currentRevision;
  }

  public long getExpectedRevision() {
    return expectedRevision;
  }
}
