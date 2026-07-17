package fun.fengwk.kkstudio.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.service.DurableCanvasService;
import fun.fengwk.kkstudio.core.studio.service.InMemoryFunctionCatalog;
import fun.fengwk.kkstudio.core.studio.service.StubFunctionRuntimeService;
import fun.fengwk.kkstudio.core.studio.service.StubResourceStore;
import fun.fengwk.kkstudio.core.studio.service.StubWorkflowCommandService;
import fun.fengwk.kkstudio.core.studio.service.StubWorkflowQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.runtime.FunctionCatalog;
import fun.fengwk.kkstudio.studio.runtime.FunctionRuntimeService;
import fun.fengwk.kkstudio.studio.runtime.ResourceStore;
import fun.fengwk.kkstudio.studio.workflow.WorkflowCommandService;
import fun.fengwk.kkstudio.studio.workflow.WorkflowQueryService;

/** Wires Studio ports. Canvas is durable; Function runtime / Workflow remain stubbed. */
@Configuration
public class StudioConfiguration {

  @Bean
  public FunctionCatalog functionCatalog() {
    return new InMemoryFunctionCatalog();
  }

  @Bean
  public ResourceStore resourceStore() {
    return new StubResourceStore();
  }

  @Bean
  public DurableCanvasService durableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandMapper commandMapper,
      ObjectMapper objectMapper) {
    return new DurableCanvasService(
        documentMapper, nodeMapper, linkMapper, commandMapper, objectMapper);
  }

  @Bean
  public CanvasQueryService canvasQueryService(DurableCanvasService durableCanvasService) {
    return durableCanvasService;
  }

  @Bean
  public CanvasCommandService canvasCommandService(DurableCanvasService durableCanvasService) {
    return durableCanvasService;
  }

  @Bean
  public WorkflowQueryService workflowQueryService() {
    return new StubWorkflowQueryService();
  }

  @Bean
  public WorkflowCommandService workflowCommandService() {
    return new StubWorkflowCommandService();
  }

  @Bean
  public FunctionRuntimeService functionRuntimeService(FunctionCatalog functionCatalog) {
    return new StubFunctionRuntimeService(functionCatalog);
  }
}
