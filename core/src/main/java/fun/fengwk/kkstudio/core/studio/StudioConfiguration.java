package fun.fengwk.kkstudio.core.studio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.studio.service.InMemoryFunctionCatalog;
import fun.fengwk.kkstudio.core.studio.service.StubCanvasCommandService;
import fun.fengwk.kkstudio.core.studio.service.StubCanvasQueryService;
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

/** Wires Studio ports. Replace stubs with durable adapters in later slices. */
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
  public CanvasQueryService canvasQueryService() {
    return new StubCanvasQueryService();
  }

  @Bean
  public CanvasCommandService canvasCommandService() {
    return new StubCanvasCommandService();
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
