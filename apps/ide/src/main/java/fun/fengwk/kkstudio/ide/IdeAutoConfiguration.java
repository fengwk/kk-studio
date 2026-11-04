package fun.fengwk.kkstudio.ide;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** IDE 应用的自动装配入口：IDE 元数据服务、跨节点转发、Daemon IDE 通道适配与 HTTP/WS 映射。 */
@ComponentScan(basePackageClasses = IdeAutoConfiguration.class)
@Configuration(proxyBeanMethods = false)
public class IdeAutoConfiguration {}
