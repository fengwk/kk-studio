package fun.fengwk.kkstudio.platform.project;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;

/**
 * 暴露 Project 领域 workflow JSON 严格编解码器为共享 bean。
 *
 * <p>编解码器是无状态纯函数，platform 只消费领域契约，不复制一份 workflow 校验规则。
 */
@Configuration(proxyBeanMethods = false)
public class ProjectWorkflowConfiguration {

  @Bean
  public ProjectWorkflowJsonCodec projectWorkflowJsonCodec() {
    return new ProjectWorkflowJsonCodec();
  }
}
