package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

import java.util.Objects;

/** 文本精确编辑时未找到 old_string 匹配项。 */
public class CloudEditPatternNotFoundException extends CloudFileSystemException {

  private final CloudPath path;

  public CloudEditPatternNotFoundException(CloudPath path) {
    super(String.format("Could not find old_string in %s", Objects.requireNonNull(path, "path")));
    this.path = path;
  }

  public CloudPath getPath() {
    return path;
  }
}
