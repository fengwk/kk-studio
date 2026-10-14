package fun.fengwk.kkstudio.plugin.minimaxmavis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

/**
 * MiniMax Mavis Plugin 的唯一装配入口，由 {@code AutoConfiguration.imports} 声明。
 *
 * <p>这是「插件是否安装」的唯一开关：本 JAR 在 classpath 上（web 的 runtime dependency 决定），就出现 Studio Plugin
 * 安装定义；把它从依赖里去掉并重新 构建，安装定义与其无状态认证依赖全部消失。
 *
 * <p>启动期只做静态冻结：构造 descriptor 与受控配置 schema，不访问 Mavis、不解析凭据。15 个模型工具、capability 缓存与工具执行器都不是容器 bean，而由
 * {@link MiniMaxMavisPlugin#configure} 按一次配置装配为归实例所有、可关闭的资源。资源网关是可选依赖：没有绑定实现时，涉及会话资源或媒体输出的调用在发送前
 * 确定性失败，不影响其余 15 个工具的注册。
 */
@AutoConfiguration
public class MiniMaxMavisAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @DependsOn("systemProxySelector")
  public MavisHttpTransport mavisHttpTransport() {
    return new JdkMavisHttpTransport();
  }

  @Bean
  @ConditionalOnMissingBean
  public MavisClient mavisClient(MavisHttpTransport mavisHttpTransport) {
    return new MavisClient(mavisHttpTransport);
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisAuthHandler mavisAuthHandler(MavisClient mavisClient) {
    return new MiniMaxMavisAuthHandler(mavisClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisCredentialRefresher mavisCredentialRefresher(MavisClient mavisClient) {
    return new MiniMaxMavisCredentialRefresher(mavisClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public StudioPlugin mavisStudioPlugin(
      MavisClient mavisClient,
      MiniMaxMavisAuthHandler mavisAuthHandler,
      MiniMaxMavisCredentialRefresher mavisCredentialRefresher) {
    return new MiniMaxMavisPlugin(mavisClient, mavisAuthHandler, mavisCredentialRefresher);
  }
}
