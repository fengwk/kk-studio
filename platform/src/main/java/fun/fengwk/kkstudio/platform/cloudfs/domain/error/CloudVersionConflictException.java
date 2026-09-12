package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 节点元数据 CAS（rename/move/delete）时 expected_version 与实际 node version 不一致时抛出。 */
public class CloudVersionConflictException extends CloudFileSystemException {

  private final CloudPath path;
  private final long currentVersion;
  private final long expectedVersion;

  public CloudVersionConflictException(CloudPath path, long currentVersion, long expectedVersion) {
    super(
        String.format(
            "Node version conflict at %s: current version is %d, expected %d",
            path, currentVersion, expectedVersion));
    this.path = path;
    this.currentVersion = currentVersion;
    this.expectedVersion = expectedVersion;
  }

  public CloudPath getPath() {
    return path;
  }

  public long getCurrentVersion() {
    return currentVersion;
  }

  public long getExpectedVersion() {
    return expectedVersion;
  }
}
