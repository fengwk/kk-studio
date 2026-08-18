package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Harness Runtime 的纯 bootstrap 部署配置：进程内 worker 开关、沙箱路径与 Redis realtime overlay 前缀。
 *
 * <p>processor/dispatcher/compaction/subagent 等运行软策略已迁移到数据库 SystemSettings（Advanced / AiRuntime /
 * Tool section），不再由本类承载。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.runtime")
public class HarnessRuntimeProperties {

  /** 是否启动本进程的 Work dispatcher / listener；关闭时控制/查询平面仍然可用。 */
  private boolean workersEnabled = true;

  /** Environment 沙箱根目录：绝对路径标准化后作为工具 workdir 的边界；未配置时取进程当前目录 （user.dir）。 */
  private Path environmentRoot = Path.of(System.getProperty("user.dir", "."));

  /** 默认工作目录：绝对路径直接使用，相对路径基于 environmentRoot 解析，必须位于 environmentRoot 之内。 */
  private Path workdir = Path.of(".");

  /** Redis realtime overlay 的 Pub/Sub channel 前缀。 */
  private String redisPrefix = "kk-studio:harness:realtime:";

  public Path resolvedEnvironmentRoot() {
    if (environmentRoot == null) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.environment-root must not be null");
    }
    return environmentRoot.toAbsolutePath().normalize();
  }

  public Path resolvedWorkdir() {
    if (workdir == null) {
      throw new IllegalArgumentException("kk-studio.harness.runtime.workdir must not be null");
    }
    Path root = resolvedEnvironmentRoot();
    Path resolved = workdir.isAbsolute() ? workdir.normalize() : root.resolve(workdir).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.workdir must stay within environment-root");
    }
    return resolved;
  }
}
