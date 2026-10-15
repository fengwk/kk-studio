package fun.fengwk.kkstudio.plugin.comfyui;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

/**
 * ComfyUI 插件的 Spring Boot 自动装配入口，由 {@code AutoConfiguration.imports} 声明。
 *
 * <p>经自动装配仅注册一个 {@link StudioPlugin} 安装定义；工作流 adapter 由配置产出的运行期实例贡献，不作为宿主内置 Bean 发布，也不再读取
 * SystemSettings 旧设置或部署环境里的明文凭据。
 */
@AutoConfiguration
public class ComfyuiAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ComfyuiPlugin comfyuiPlugin() {
    return new ComfyuiPlugin();
  }
}
