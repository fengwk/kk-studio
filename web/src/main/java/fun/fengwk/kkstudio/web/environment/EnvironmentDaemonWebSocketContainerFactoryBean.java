package fun.fengwk.kkstudio.web.environment;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Tolerant variant of {@link ServletServerContainerFactoryBean} for the Daemon v1 Environment
 * gateway. Detects whether the JSR-356 {@link ServerContainer} attribute is actually present in the
 * {@link ServletContext} and only delegates to the Spring standard configuration path when it is;
 * otherwise the bean becomes a no-op so non-container Spring contexts (MockMvc, {@code
 * WebEnvironment.MOCK}) can boot without an embedded servlet container.
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
      // Non-container context (e.g. MockMvc / WebEnvironment.MOCK): the JSR-356 ServerContainer
      // attribute is never published. Skip the Spring standard configuration path entirely so
      // Spring's strict "Attribute not found in ServletContext" assertion never fires.
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
