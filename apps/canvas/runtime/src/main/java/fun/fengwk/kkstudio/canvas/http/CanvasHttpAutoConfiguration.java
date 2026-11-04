package fun.fengwk.kkstudio.canvas.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

/**
 * Canvas HTTP 边界装配：扫描本包的控制器，由宿主（Web 组合根）启动时注册。
 *
 * <p>控制器与 Canvas 运行时同模块，经 Spring Boot 自动配置进入宿主的 MVC 上下文，因此宿主不需要知道 Canvas 的控制器类型； 独立 Canvas
 * 运行时（模块测试）不扫描本包。扫描沿用 Spring Boot 的 {@link TypeExcludeFilter}，测试专用配置只在显式导入时生效。
 */
@AutoConfiguration
@ComponentScan(
    excludeFilters = @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class CanvasHttpAutoConfiguration {}
