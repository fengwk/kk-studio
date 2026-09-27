package fun.fengwk.kkstudio.project;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;

/**
 * Project 模块的自动装配入口：把 Project 领域 Bean、领域编解码器与 MyBatis Mapper 注册到宿主应用。
 *
 * <p>Project 不是 Spring Boot 启动入口；宿主（web）经本自动配置装配模块内的 service、repository 与 mapper。跨宿主能力（全局
 * Blob、命令接受、Agent 分支设置、Session 深删除）由宿主实现 {@code fun.fengwk.kkstudio.project.port} 下的 Port
 * 后注入，Project 绝不反向依赖宿主。
 */
@BaseMapperScan("fun.fengwk.kkstudio.project")
@ComponentScan(basePackageClasses = ProjectAutoConfiguration.class)
@Configuration
public class ProjectAutoConfiguration {

  /** workflow 配置的严格 JSON 编解码器：无状态纯函数，Project 与消费它的宿主组件共享同一份实现，避免复制校验规则。 */
  @Bean
  public ProjectWorkflowJsonCodec projectWorkflowJsonCodec() {
    return new ProjectWorkflowJsonCodec();
  }
}
