package fun.fengwk.kkstudio.plugin.media;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

/**
 * Media 插件的 Spring Boot 自动装配入口，由 {@code AutoConfiguration.imports} 声明。
 *
 * <p>经自动装配仅注册一个 {@link StudioPlugin} 安装定义；函数 adapter 由配置产出的运行期实例贡献，不作为宿主内置 Bean 发布。
 */
@AutoConfiguration
public class MediaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public MediaPlugin mediaPlugin() {
    return new MediaPlugin();
  }
}
