package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Harness Runtime 的纯 bootstrap 部署配置：进程内 worker 开关与 Backend 资源存储根。
 *
 * <p>Processor、compaction 与 subagent 等运行软策略由数据库 SystemSettings 承载；Dispatcher 容量与调度节奏由独立的 {@link
 * HarnessDispatcherProperties} 承载。
 *
 * <p>{@code resource-root} 只是 Backend 内容寻址资源存储的宿主目录。工具目录由每次调用的具体 arguments 提供，Backend 不持有默认
 * cwd、目录边界或 Environment 根配置。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.runtime")
public class HarnessRuntimeProperties {

  /** 是否启动本进程的 Work dispatcher / listener；关闭时控制/查询平面仍然可用。 */
  private boolean workersEnabled = true;

  /** Backend 内容寻址资源存储根目录；未配置时取进程当前目录下的 {@code .kkstudio/resources}。 */
  private Path resourceRoot =
      Path.of(System.getProperty("user.dir", "."), ".kkstudio", "resources");

  public Path resolvedResourceRoot() {
    if (resourceRoot == null) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.resource-root must not be null");
    }
    return resourceRoot.toAbsolutePath().normalize();
  }
}
