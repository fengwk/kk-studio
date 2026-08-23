package fun.fengwk.kkstudio.web.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.plugins.goal.GoalPlugin;

/** Web 组合根注册随应用交付的受信任构建期插件。 */
@Configuration(proxyBeanMethods = false)
public class BuiltInPluginConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "goalPlugin")
  public GoalPlugin goalPlugin() {
    return new GoalPlugin();
  }
}
