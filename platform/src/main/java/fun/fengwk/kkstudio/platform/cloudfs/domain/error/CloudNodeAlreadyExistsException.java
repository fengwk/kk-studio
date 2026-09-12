package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 目标路径已存在同名节点时抛出。 */
public class CloudNodeAlreadyExistsException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudNodeAlreadyExistsException(CloudPath path) {
    super("Cloud node already exists at path: " + path);
    this.path = path;
  }

  public CloudPath getPath() {
    return path;
  }
}
