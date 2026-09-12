package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 文本精确编辑时未找到 old_string 匹配项。 */
public class CloudEditPatternNotFoundException extends CloudFileSystemException {

  private final CloudPath path;
  private final String oldString;

  public CloudEditPatternNotFoundException(CloudPath path, String oldString) {
    super(String.format("Target string not found in %s: %s", path, oldString));
    this.path = path;
    this.oldString = oldString;
  }

  public CloudPath getPath() {
    return path;
  }

  public String getOldString() {
    return oldString;
  }
}
