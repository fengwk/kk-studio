package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 尝试对受保护的系统路径（如 {@code /.artifacts} 或 root {@code /}）执行公开写/删/移等禁止操作时抛出。 */
public class CloudPathForbiddenException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudPathForbiddenException(CloudPath path, String message) {
    super(message);
    this.path = path;
  }

  public CloudPath getPath() {
    return path;
  }
}
