package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 目标路径或节点不存在时抛出。 */
public class CloudNodeNotFoundException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudNodeNotFoundException(CloudPath path) {
    super("Cloud node not found at path: " + path);
    this.path = path;
  }

  public CloudNodeNotFoundException(String message) {
    super(message);
    this.path = null;
  }

  public CloudPath getPath() {
    return path;
  }
}
