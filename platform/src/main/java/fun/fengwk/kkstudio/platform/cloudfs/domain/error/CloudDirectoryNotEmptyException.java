package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 尝试删除非空目录时抛出。 */
public class CloudDirectoryNotEmptyException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudDirectoryNotEmptyException(CloudPath path) {
    super("Cannot delete non-empty directory at path: " + path);
    this.path = path;
  }

  public CloudPath getPath() {
    return path;
  }
}
