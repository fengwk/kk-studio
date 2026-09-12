package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 尝试删除非空目录时抛出。 */
public class CloudDirectoryNotEmptyException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudDirectoryNotEmptyException(CloudPath path, int childCount) {
    super(
        String.format(
            "Cannot delete non-empty directory at path: %s (contains %d children)",
            path, childCount));
    this.path = path;
  }

  public CloudPath getPath() {
    return path;
  }
}
