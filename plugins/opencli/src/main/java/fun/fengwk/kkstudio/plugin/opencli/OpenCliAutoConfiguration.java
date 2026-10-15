package fun.fengwk.kkstudio.plugin.opencli;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

/**
 * OpenCLI 插件的 Spring Boot 自动装配入口，由 {@code AutoConfiguration.imports} 声明。
 *
 * <p>经自动装配仅注册一个 {@link StudioPlugin} 安装定义；不在容器中发布 client 或 adapter 为宿主内置 Bean。
 */
@AutoConfiguration
public class OpenCliAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OpenCliPlugin openCliPlugin(ObjectProvider<ObjectMapper> objectMapperProvider) {
    ObjectMapper mapper = objectMapperProvider.getIfAvailable(() -> JsonMapper.builder().build());
    return new OpenCliPlugin(mapper);
  }
}
