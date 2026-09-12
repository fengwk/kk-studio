package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 移动目录产生闭环或移动到自身后代时抛出。 */
public class CloudCycleException extends CloudFileSystemException {

  private final CloudPath sourcePath;
  private final CloudPath targetPath;

  public CloudCycleException(CloudPath sourcePath, CloudPath targetPath) {
    super(
        String.format(
            "Cannot move directory %s into itself or its descendant %s", sourcePath, targetPath));
    this.sourcePath = sourcePath;
    this.targetPath = targetPath;
  }

  public CloudPath getSourcePath() {
    return sourcePath;
  }

  public CloudPath getTargetPath() {
    return targetPath;
  }
}
