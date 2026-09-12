package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** Tool Artifact 路径冲突且 blob 大小或 hash 不匹配时抛出（不变量违规，禁止覆盖）。 */
public class CloudArtifactConflictException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudArtifactConflictException(CloudPath path, String message) {
    super(message);
    this.path = path;
  }

  public CloudPath getPath() {
    return path;
  }
}
