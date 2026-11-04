package fun.fengwk.kkstudio.canvas.infra;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;
import fun.fengwk.kkstudio.canvas.notification.CanvasVersionHub;

/**
 * Canvas 运行时装配：PostgreSQL 持久化、codec、Function Runtime 与 revision 通知 Hub。
 *
 * <p>平台 Blob 适配在 {@code platform} 子包，由 {@code CanvasPlatformAutoConfiguration} 单独装配；扫描沿用 Spring
 * Boot 的 {@link TypeExcludeFilter}，测试专用配置只在显式导入时生效。
 *
 * <p>{@link CanvasVersionHub} 位于 {@code notification} 包，不参与组件扫描：它只依赖领域读端口，由本配置显式注册，宿主据此注册 LISTEN
 * 并把通知交给它。
 */
@ComponentScan(
    excludeFilters = {
      @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @Filter(
          type = FilterType.REGEX,
          pattern = "fun\\.fengwk\\.kkstudio\\.canvas\\.infra\\.platform\\..*")
    })
@EnableConfigurationProperties(CanvasFunctionRuntimeProperties.class)
@Configuration(proxyBeanMethods = false)
public class CanvasInfraAutoConfiguration {

  @Bean
  public CanvasVersionHub canvasVersionHub(CanvasQueryService canvasQueryService) {
    return new CanvasVersionHub(canvasQueryService);
  }
}
