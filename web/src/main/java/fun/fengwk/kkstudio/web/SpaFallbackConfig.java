package fun.fengwk.kkstudio.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA fallback MVC 配置。
 *
 * <p>注册静态资源处理器 {@code /**} -> {@code classpath:/static/}，并在解析链中插入 {@link
 * SpaFallbackResourceResolver}。当真实静态资源（如 {@code /assets/index-xxx.js}、 {@code /favicon.svg} 等）存在时由
 * {@code PathResourceResolver} 正常返回；缺失且满足 fallback 条件的请求才被改写为 {@code index.html}，用于承载前端
 * BrowserRouter 的刷新回退。
 *
 * @author fengwk
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new SpaFallbackResourceResolver());
  }
}
