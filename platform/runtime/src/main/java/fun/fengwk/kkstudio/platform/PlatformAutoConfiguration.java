package fun.fengwk.kkstudio.platform;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * @author fengwk
 */
@ComponentScan(
    basePackageClasses = PlatformAutoConfiguration.class,
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
@Configuration
public class PlatformAutoConfiguration {}
