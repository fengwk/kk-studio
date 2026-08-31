package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Daemon invoke 请求的入站 workspace 路径规范化与 timeout 解析。
 *
 * <p>本类无生命周期状态，副作用仅限文件系统真实路径查询与存在性校验，不持有执行生命周期资源（如 journal、transport、running map、scheduler 或
 * executor）。
 */
final class InvocationRequestNormalizer {

  private final Path environmentRoot;
  private final Duration defaultToolTimeout;

  InvocationRequestNormalizer(Path environmentRoot, Duration defaultToolTimeout) {
    this.environmentRoot = Objects.requireNonNull(environmentRoot, "environmentRoot");
    this.defaultToolTimeout = Objects.requireNonNull(defaultToolTimeout, "defaultToolTimeout");
  }

  /**
   * 将相对 workspace wire 路径规范化为 Environment Root 内的现存真实目录路径。
   *
   * @param workspacePath 相对 wire 路径文本
   * @return 解析并验证后的绝对规范化目录路径
   * @throws IllegalArgumentException 当路径形状非法、不存在、越界或不是目录时抛出
   */
  Path canonicalWorkspace(String workspacePath) {
    EnvironmentWorkspacePath.requireCanonicalRelativePath(workspacePath);
    Path candidate = environmentRoot.resolve(Path.of(workspacePath)).normalize();
    Path canonical;
    try {
      canonical = candidate.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "workspace does not resolve to an existing directory: " + workspacePath, error);
    }
    if (!canonical.startsWith(environmentRoot)) {
      throw new IllegalArgumentException("workspace escapes environment root: " + workspacePath);
    }
    if (!Files.isDirectory(canonical)) {
      throw new IllegalArgumentException("workspace is not a directory: " + workspacePath);
    }
    return canonical;
  }

  /**
   * 按照 requested -> descriptor -> daemon default 三层 fallback 规则解析有效执行超时。
   *
   * @param requestedTimeout 请求中携带的超时（0 表示未指定）
   * @param descriptor Capability 描述符
   * @return 解析后的有效超时
   */
  Duration resolveTimeout(Duration requestedTimeout, EnvironmentCapabilityDescriptor descriptor) {
    Objects.requireNonNull(requestedTimeout, "requestedTimeout");
    Objects.requireNonNull(descriptor, "descriptor");
    if (!requestedTimeout.isZero()) {
      return requestedTimeout;
    }
    return descriptor.timeout().isZero() ? defaultToolTimeout : descriptor.timeout();
  }
}
