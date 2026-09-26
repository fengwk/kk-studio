package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.CanvasCommandPlanner;

import java.time.Clock;
import java.util.UUID;

/**
 * Canvas 领域命令规划的装配。
 *
 * <p>规划是纯领域计算，只依赖时间来源与新 Resource id 的分配来源；Resource id 由服务端生成，因此不依赖数据库序列。
 */
@Configuration(proxyBeanMethods = false)
public class CanvasCommandPlanningConfiguration {

  @Bean
  public CanvasCommandPlanner canvasCommandPlanner(Clock clock) {
    return new CanvasCommandPlanner(clock, UUID::randomUUID);
  }
}
