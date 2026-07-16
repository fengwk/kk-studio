package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link ComfyuiWorkflowApiBindingsParser} 与 {@link ComfyuiWorkflowApiLookupService} 作为 Spring
 * 组件暴露，供 runtime 与 repository 调用方复用。
 *
 * @author fengwk
 */
@Configuration
public class ComfyuiWorkflowApiAutoConfiguration {

  @Bean
  public ComfyuiWorkflowApiBindingsParser comfyuiWorkflowApiBindingsParser(
      ObjectMapper objectMapper) {
    return new ComfyuiWorkflowApiBindingsParser(objectMapper);
  }

  @Bean
  public ComfyuiWorkflowApiLookupService comfyuiWorkflowApiLookupService(
      ComfyuiWorkflowApiRepository comfyuiWorkflowApiRepository,
      ComfyuiWorkflowApiBindingsParser comfyuiWorkflowApiBindingsParser) {
    return new ComfyuiWorkflowApiLookupService(
        comfyuiWorkflowApiRepository, comfyuiWorkflowApiBindingsParser);
  }
}
