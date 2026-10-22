package fun.fengwk.kkstudio.canvas.infra;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;

/**
 * Canvas 运行时装配：PostgreSQL 持久化、codec 与 Function Runtime。
 *
 * <p>平台 Blob 适配在 {@code platform} 子包，由 {@code CanvasPlatformAutoConfiguration} 单独装配；扫描沿用 Spring
 * Boot 的 {@link TypeExcludeFilter}，测试专用配置只在显式导入时生效。
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
public class CanvasInfraAutoConfiguration {}
