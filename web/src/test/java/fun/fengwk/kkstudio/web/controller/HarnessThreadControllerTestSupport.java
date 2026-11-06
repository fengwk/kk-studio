package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.harness.http.HarnessThreadService;
import fun.fengwk.kkstudio.platform.harness.thread.query.EnvironmentWaitProjectionService;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.Map;
import java.util.UUID;

/**
 * Thread API 端点族测试的公共装配：真实应用服务 + mocked Runtime/投影/交互，MockMvc 只挂统一错误 advice。
 *
 * <p>各端点族测试只保留自己的行为断言；费用投影默认无计价事实，单个用例再按 entry id 指定。
 */
abstract class HarnessThreadControllerTestSupport {

  protected HarnessRuntime runtime;
  protected ModelRequestDebugService modelRequestDebugService;
  protected InteractionService interactionService;
  protected UsageCostProjectionService usageCostProjectionService;
  protected EnvironmentService environmentService;
  protected MockMvc mockMvc;

  @BeforeEach
  void setUpThreadController() {
    runtime = mock(HarnessRuntime.class);
    modelRequestDebugService = mock(ModelRequestDebugService.class);
    interactionService = mock(InteractionService.class);
    usageCostProjectionService = mock(UsageCostProjectionService.class);
    environmentService = mock(EnvironmentService.class);
    when(usageCostProjectionService.project(any())).thenReturn(Map.of());
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(
            runtime,
            new HarnessThreadService(
                runtime,
                usageCostProjectionService,
                new EnvironmentWaitProjectionService(runtime, environmentService)),
            modelRequestDebugService,
            interactionService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioProblemAdvice(new StudioMessageService()), new ApiResponseStatusAdvice())
            .build();
  }

  protected static UUID id(long value) {
    return HarnessRuntimeTestFixtures.id(value);
  }

  protected static String idText(long value) {
    return HarnessRuntimeTestFixtures.idText(value);
  }
}
