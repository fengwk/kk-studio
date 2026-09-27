package fun.fengwk.kkstudio.plugin.canvasmedia;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Canvas Media Plugin 的 Spring Boot 自动装配入口。
 *
 * <p>提供本地无模型依赖的图像裁剪 {@link CanvasMediaFunctionAdapter}。
 */
@AutoConfiguration
public class CanvasMediaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public CanvasMediaFunctionAdapter canvasMediaFunctionAdapter() {
    return new CanvasMediaFunctionAdapter();
  }
}
