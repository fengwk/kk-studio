package fun.fengwk.kkstudio.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * SPA fallback MVC 配置。
 *
 * <p>注册静态资源处理器 {@code /**} -> {@code classpath:/static/}，并在解析链中插入 {@link
 * SpaFallbackResourceResolver}。当真实静态资源（如 {@code /assets/index-xxx.js}、 {@code /favicon.svg} 等）存在时由
 * {@code PathResourceResolver} 正常返回；缺失且满足 fallback 条件的请求才被改写为 {@code index.html}，用于承载前端
 * BrowserRouter 的刷新回退。
 *
 * <p>Vite 把构建产物写入 {@code /assets/} 且文件名带内容 hash，因此该命名空间单独注册为长缓存 + {@code
 * immutable}；其余静态文件不带缓存策略，SPA 外壳由 {@link SpaShellNoCacheFilter} 要求每次回源验证。
 *
 * @author fengwk
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

  /** 内容 hash 保证同名文件内容不变，可按文件名长期缓存。 */
  private static final CacheControl HASHED_ASSETS =
      CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(HASHED_ASSETS);
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new SpaFallbackResourceResolver());
  }
}
