package fun.fengwk.kkstudio.web.controller;

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

  @Test
  void listEnvironmentsReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    card.setStatus("READY");
    when(environmentService.list()).thenReturn(List.of(card));

    mockMvc
        .perform(get("/api/ai/environments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data[0].name").value("dev"));
  }

  /** 验证查询已存在的 Environment 返回 200 与对应的卡片信息。 */
  @Test
  void getEnvironmentReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/ai/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("dev"));
  }

  /**
   * 验证当服务层抛出 AiResourceNotFoundException 时，由统一 StudioDomainErrorAdvice 映射为 HTTP 404 与
   * RESOURCE_NOT_FOUND (code="resource_not_found") 错误信封，而不是泄露为 500。
   */
  @Test
  void getMissingEnvironmentReturnsNotFound() throws Exception {
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(
            new AiResourceNotFoundException("environment", "environment not found: " + ENV_ID));

    mockMvc
        .perform(get("/api/ai/environments/" + ENV_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("resource_not_found"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value("environment not found: " + ENV_ID));
  }

  @Test
  void createEnvironmentReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("new-env");
    card.setRegistrationToken("secret-token");
    when(environmentService.create(any(EnvironmentCreateDTO.class))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/ai/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-env\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("secret-token"));
  }

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
            put("/api/ai/environments/" + ENV_ID)
                .param("expectedVersion", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"updated-env\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("updated-env"));
  }

  @Test
  void rotateTokenReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setRegistrationToken("new-secret-token");
    when(environmentService.rotateToken(eq(EnvironmentId.of(ENV_ID)), eq("0"))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/ai/environments/" + ENV_ID + "/registration-token")
                .param("expectedVersion", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.registrationToken").value("new-secret-token"));
  }

  @Test
  void deleteEnvironmentReturnsOk() throws Exception {
    mockMvc
        .perform(delete("/api/ai/environments/" + ENV_ID).param("expectedVersion", "0"))
        .andExpect(status().isOk());

    verify(environmentService).delete(EnvironmentId.of(ENV_ID), "0");
  }
}
