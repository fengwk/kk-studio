package fun.fengwk.kkstudio.web.environment;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * {@link ServletServerContainerFactoryBean} 面向 Daemon 端点 v2（WebSocket 路径
 * `/api/ai/environment/daemon/v2`）的宽容变体。检测 JSR-356 {@link ServerContainer} 属性是否真的存在于 {@link
 * ServletContext} 中，仅在其存在时才委托给 Spring 标准配置路径；否则该 bean 变为 no-op，使非容器 Spring 上下文（MockMvc、{@code
 * WebEnvironment.MOCK}） 无需内嵌 servlet container 即可启动。
 */
final class EnvironmentDaemonWebSocketContainerFactoryBean
    extends ServletServerContainerFactoryBean {

  private ServletContext servletContext;

  @Override
  public void setServletContext(ServletContext servletContext) {
    super.setServletContext(servletContext);
    this.servletContext = servletContext;
  }

  @Override
  public void afterPropertiesSet() {
    if (!hasServerContainer()) {
      // 非容器上下文（如 MockMvc / WebEnvironment.MOCK）：JSR-356 ServerContainer 属性永远不会发布。
      // 完全跳过 Spring 标准配置路径，避免 Spring 抛出严格的 "Attribute not found in ServletContext" 断言。
      return;
    }
    super.afterPropertiesSet();
  }

  private boolean hasServerContainer() {
    if (this.servletContext == null) {
      return false;
    }
    Object attribute = this.servletContext.getAttribute(ServerContainer.class.getName());
    return attribute instanceof ServerContainer;
  }
}
