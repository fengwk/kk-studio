package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.List;
import java.util.UUID;

class StudioEnvironmentControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private EnvironmentService environmentService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentService = mock(EnvironmentService.class);
    StudioEnvironmentController controller = new StudioEnvironmentController(environmentService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioDomainErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /** 意图：验证 GET /api/harness/environments 列表查询返回 200 与卡片列表。 */
  @Test
  void listEnvironmentsReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    card.setStatus("READY");
    when(environmentService.list()).thenReturn(List.of(card));

    mockMvc
        .perform(get("/api/harness/environments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data[0].name").value("dev"));
  }

  /** 意图：验证 GET /api/harness/environments/{environmentId} 查询已存在的环境返回 200 与对应的卡片信息。 */
  @Test
  void getEnvironmentReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("dev"));
  }

  /**
   * 意图：验证当服务层抛出 AiResourceNotFoundException 时，由统一 StudioDomainErrorAdvice 映射为 HTTP 404 与
   * RESOURCE_NOT_FOUND (code="resource_not_found") 错误信封，而不是泄露为 500。
   */
  @Test
  void getMissingEnvironmentReturnsNotFound() throws Exception {
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(
            new AiResourceNotFoundException("environment", "environment not found: " + ENV_ID));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("resource_not_found"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value("environment not found: " + ENV_ID));
  }

  /** 意图：验证 POST /api/harness/environments 创建环境成功返回 HTTP 201 Created。 */
  @Test
  void createEnvironmentReturnsCreated() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("new-env");
    card.setRegistrationToken("secret-token");
    when(environmentService.create(any(EnvironmentCreateDTO.class))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/harness/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-env\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("secret-token"));
  }

  /**
   * 意图：验证 PUT /api/harness/environments/{environmentId} 的 expectedVersion 必须从 body 读取并执行 CAS 更新，返回
   * 200。
   */
  @Test
  void updateEnvironmentReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("updated-env");
    when(environmentService.update(
            eq(EnvironmentId.of(ENV_ID)), any(EnvironmentUpdateDTO.class), eq("0")))
        .thenReturn(card);

    mockMvc
        .perform(
            put("/api/harness/environments/" + ENV_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"updated-env\",\"expectedVersion\":\"0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("updated-env"));

    ArgumentCaptor<EnvironmentUpdateDTO> dtoCaptor =
        ArgumentCaptor.forClass(EnvironmentUpdateDTO.class);
    verify(environmentService).update(eq(EnvironmentId.of(ENV_ID)), dtoCaptor.capture(), eq("0"));
    assertEquals("updated-env", dtoCaptor.getValue().getName());
    assertEquals("0", dtoCaptor.getValue().getExpectedVersion());
  }

  /**
   * 意图：验证 POST /api/harness/environments/{environmentId}/registration-token 从请求体读取
   * {expectedVersion} 并轮换 token，返回 200。
   */
  @Test
  void rotateTokenReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setRegistrationToken("new-secret-token");
    when(environmentService.rotateToken(eq(EnvironmentId.of(ENV_ID)), eq("0"))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/registration-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.registrationToken").value("new-secret-token"));

    verify(environmentService).rotateToken(EnvironmentId.of(ENV_ID), "0");
  }

  /**
   * 意图：验证 DELETE /api/harness/environments/{environmentId} 依然通过 query param 传递 expectedVersion，并返回
   * HTTP 204 No Content。
   */
  @Test
  void deleteEnvironmentReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/harness/environments/" + ENV_ID).param("expectedVersion", "0"))
        .andExpect(status().isNoContent());

    verify(environmentService).delete(EnvironmentId.of(ENV_ID), "0");
  }
}
