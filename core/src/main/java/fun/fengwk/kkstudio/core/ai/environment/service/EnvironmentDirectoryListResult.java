package fun.fengwk.kkstudio.core.ai.environment.service;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryFailureCode;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;

import java.util.Objects;

/** 一次单层目录浏览请求的有界异步结果。 */
public sealed interface EnvironmentDirectoryListResult
    permits EnvironmentDirectoryListResult.Loaded, EnvironmentDirectoryListResult.Failed {

  /** Daemon 返回了一层目录列表（wire {@code DIRECTORY_LISTED} 的应用读模型）。 */
  record Loaded(EnvironmentDirectoryDTO listing) implements EnvironmentDirectoryListResult {
    public Loaded {
      listing = Objects.requireNonNull(listing, "listing");
    }
  }

  /** 非法路径、daemon 失败、离线或超时等确定性失败。 */
  record Failed(DaemonDirectoryFailureCode code, String message)
      implements EnvironmentDirectoryListResult {
    public Failed {
      code = Objects.requireNonNull(code, "code");
      message = requireNonBlank(message, "message");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
