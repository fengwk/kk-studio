package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.operation.DuplicateActiveOperationException;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationResourceType;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationService;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationType;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillInventoryQueryService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSourceService;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInventoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceUpdateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.List;
import java.util.UUID;

class StudioEnvironmentControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SOURCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private EnvironmentService environmentService;
  private EnvironmentSkillSourceService skillSourceService;
  private EnvironmentSkillInventoryQueryService skillInventoryQueryService;
  private EnvironmentOperationService operationService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentService = mock(EnvironmentService.class);
    skillSourceService = mock(EnvironmentSkillSourceService.class);
    skillInventoryQueryService = mock(EnvironmentSkillInventoryQueryService.class);
    operationService = mock(EnvironmentOperationService.class);

    StudioEnvironmentController controller =
        new StudioEnvironmentController(
            environmentService, skillSourceService, skillInventoryQueryService, operationService);
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
        .andExpect(jsonPath("$.data[0].name").value("dev"))
        .andExpect(jsonPath("$.data[0].skills").doesNotExist());
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
        .andExpect(jsonPath("$.data.name").value("dev"))
        .andExpect(jsonPath("$.data.skills").doesNotExist());
  }

  /**
   * 意图：验证当服务层抛出 AiResourceNotFoundException 时，由统一 StudioDomainErrorAdvice 映射为 HTTP 404 与
   * RESOURCE_NOT_FOUND (code="resource_not_found") 错误信封，而不是泄露为 500。
   */
  @Test
  void getMissingEnvironmentReturnsNotFound() throws Exception {
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(new AiResourceNotFoundException("environment"));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("resource_not_found"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value("environment not found"));
  }

  /** 意图：验证 POST /api/harness/environments 创建环境成功返回 HTTP 201 Created，且携带凭据的响应禁止缓存。 */
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
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("secret-token"));
  }

  /**
   * 意图：验证 GET /api/harness/environments/{id}/token 是幂等只读端点，返回当前 token 与版本，并通过 Cache-Control:
   * no-store 明确禁止任何缓存；它不得调用 rotateToken。
   */
  @Test
  void getRegistrationTokenReturnsCurrentTokenWithNoStore() throws Exception {
    EnvironmentRegistrationTokenDTO token = new EnvironmentRegistrationTokenDTO();
    token.setId(ENV_ID.toString());
    token.setRegistrationToken("current-token");
    token.setVersion("4");
    when(environmentService.getRegistrationToken(eq(EnvironmentId.of(ENV_ID)))).thenReturn(token);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/token"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("current-token"))
        .andExpect(jsonPath("$.data.version").value("4"));

    verify(environmentService).getRegistrationToken(EnvironmentId.of(ENV_ID));
    verify(environmentService, never()).rotateToken(any(), any());
  }

  /**
   * 意图：验证 GET /api/harness/environments/{id} 普通详情只返回 Card 投影，绝不返回 registrationToken（凭据不进入列表/详情
   * 缓存路径）。
   */
  @Test
  void getEnvironmentNeverReturnsRegistrationToken() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").doesNotExist());
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
   * {expectedVersion} 并轮换 token，返回 200 且禁止缓存。
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
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
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

  /** 意图：验证 GET /api/harness/environments/{envId}/skill-sources 成功返回 200 与来源列表。 */
  @Test
  void listSkillSourcesReturnsOk() throws Exception {
    EnvironmentSkillSourceDTO source = new EnvironmentSkillSourceDTO();
    source.setSourceId(SOURCE_ID.toString());
    source.setType("git");
    when(skillSourceService.list(eq(EnvironmentId.of(ENV_ID)))).thenReturn(List.of(source));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/skill-sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sourceId").value(SOURCE_ID.toString()))
        .andExpect(jsonPath("$.data[0].type").value("git"));
  }

  /** 意图：验证 GET /api/harness/environments/{envId}/skill-sources/{srcId} 成功返回 200 与来源详情。 */
  @Test
  void getSkillSourceReturnsOk() throws Exception {
    EnvironmentSkillSourceDTO source = new EnvironmentSkillSourceDTO();
    source.setSourceId(SOURCE_ID.toString());
    source.setType("git");
    when(skillSourceService.get(eq(EnvironmentId.of(ENV_ID)), eq(SOURCE_ID))).thenReturn(source);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sourceId").value(SOURCE_ID.toString()))
        .andExpect(jsonPath("$.data.type").value("git"));
  }

  /** 意图：验证 POST /api/harness/environments/{envId}/skill-sources 注册新来源返回 201 Created。 */
  @Test
  void registerSkillSourceReturnsCreated() throws Exception {
    EnvironmentSkillSourceDTO source = new EnvironmentSkillSourceDTO();
    source.setSourceId(SOURCE_ID.toString());
    source.setType("git");
    when(skillSourceService.create(
            eq(EnvironmentId.of(ENV_ID)), any(EnvironmentSkillSourceCreateDTO.class)))
        .thenReturn(source);

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/skill-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"git\",\"gitUrl\":\"https://github.com/example/skills\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.sourceId").value(SOURCE_ID.toString()))
        .andExpect(jsonPath("$.data.type").value("git"));
  }

  /** 意图：验证 PUT /api/harness/environments/{envId}/skill-sources/{srcId} 更新来源返回 200 OK。 */
  @Test
  void updateSkillSourceReturnsOk() throws Exception {
    EnvironmentSkillSourceDTO source = new EnvironmentSkillSourceDTO();
    source.setSourceId(SOURCE_ID.toString());
    source.setType("git");
    when(skillSourceService.update(
            eq(EnvironmentId.of(ENV_ID)),
            eq(SOURCE_ID),
            any(EnvironmentSkillSourceUpdateDTO.class)))
        .thenReturn(source);

    mockMvc
        .perform(
            put("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gitRef\":\"main\",\"expectedVersion\":\"1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sourceId").value(SOURCE_ID.toString()));
  }

  /** 意图：验证 DELETE /api/harness/environments/{envId}/skill-sources/{srcId} 删除来源返回 204 No Content。 */
  @Test
  void deleteSkillSourceReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID)
                .param("expectedVersion", "1"))
        .andExpect(status().isNoContent());

    verify(skillSourceService).delete(EnvironmentId.of(ENV_ID), SOURCE_ID, "1");
  }

  /** 意图：验证非规范 UUID（包含大写字母）由严格校验拦截并返回 400 Bad Request，且不回显非法值。 */
  @Test
  void nonCanonicalUuidReturnsBadRequestWithoutEchoingInput() throws Exception {
    String invalidSourceId = "22222222-2222-2222-2222-22222222222A"; // uppercase 'A'
    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/skill-sources/" + invalidSourceId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(
            result ->
                assertFalse(result.getResponse().getContentAsString().contains(invalidSourceId)));
  }

  /** 意图：验证 GET /api/harness/environments/{envId}/inventory 查询全量持久化清单返回 200 OK。 */
  @Test
  void getInventoryReturnsOk() throws Exception {
    EnvironmentInventoryDTO inventory = new EnvironmentInventoryDTO();
    inventory.setEnvironmentId(ENV_ID.toString());
    when(skillInventoryQueryService.getInventory(eq(EnvironmentId.of(ENV_ID))))
        .thenReturn(inventory);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/inventory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.environmentId").value(ENV_ID.toString()));
  }

  /**
   * 意图：验证 GET /api/harness/environments/{envId}/inventory/skills 默认查询（usableOnly=false）调用
   * listSkills。
   */
  @Test
  void listSkillsDefaultUsableOnlyCallsListSkills() throws Exception {
    EnvironmentSkillDTO skill = new EnvironmentSkillDTO();
    skill.setName("bash");
    when(skillInventoryQueryService.listSkills(eq(EnvironmentId.of(ENV_ID))))
        .thenReturn(List.of(skill));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/inventory/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("bash"));

    verify(skillInventoryQueryService).listSkills(EnvironmentId.of(ENV_ID));
    verify(skillInventoryQueryService, never()).listUsableSkills(any());
  }

  /**
   * 意图：验证 GET /api/harness/environments/{envId}/inventory/skills?usableOnly=true 调用
   * listUsableSkills。
   */
  @Test
  void listSkillsUsableOnlyCallsListUsableSkills() throws Exception {
    EnvironmentSkillDTO skill = new EnvironmentSkillDTO();
    skill.setName("bash");
    when(skillInventoryQueryService.listUsableSkills(eq(EnvironmentId.of(ENV_ID))))
        .thenReturn(List.of(skill));

    mockMvc
        .perform(
            get("/api/harness/environments/" + ENV_ID + "/inventory/skills")
                .param("usableOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("bash"));

    verify(skillInventoryQueryService).listUsableSkills(EnvironmentId.of(ENV_ID));
    verify(skillInventoryQueryService, never()).listSkills(any());
  }

  /** 意图：验证 POST /api/harness/environments/{envId}/skill-sources/{srcId}/refresh 返回 202 Accepted。 */
  @Test
  void refreshSkillSourceReturnsAccepted() throws Exception {
    EnvironmentOperationDTO op = new EnvironmentOperationDTO();
    op.setId(OP_ID.toString());
    op.setStatus("PENDING");
    op.setOperationType("SKILL_REFRESH");
    when(operationService.create(
            eq(EnvironmentId.of(ENV_ID)),
            eq(SOURCE_ID),
            eq(EnvironmentOperationType.SKILL_REFRESH),
            any()))
        .thenReturn(op);

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID + "/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.id").value(OP_ID.toString()))
        .andExpect(jsonPath("$.data.operationType").value("SKILL_REFRESH"));
  }

  /** 意图：验证 POST /api/harness/environments/{envId}/skill-sources/{srcId}/install 返回 202 Accepted。 */
  @Test
  void installSkillSourceReturnsAccepted() throws Exception {
    EnvironmentOperationDTO op = new EnvironmentOperationDTO();
    op.setId(OP_ID.toString());
    op.setStatus("PENDING");
    op.setOperationType("SKILL_INSTALL");
    when(operationService.create(
            eq(EnvironmentId.of(ENV_ID)),
            eq(SOURCE_ID),
            eq(EnvironmentOperationType.SKILL_INSTALL),
            any()))
        .thenReturn(op);

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID + "/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.id").value(OP_ID.toString()))
        .andExpect(jsonPath("$.data.operationType").value("SKILL_INSTALL"));
  }

  /** 意图：验证 POST /api/harness/environments/{envId}/skill-sources/{srcId}/update 返回 202 Accepted。 */
  @Test
  void updateSkillSourceOpReturnsAccepted() throws Exception {
    EnvironmentOperationDTO op = new EnvironmentOperationDTO();
    op.setId(OP_ID.toString());
    op.setStatus("PENDING");
    op.setOperationType("SKILL_UPDATE");
    when(operationService.create(
            eq(EnvironmentId.of(ENV_ID)),
            eq(SOURCE_ID),
            eq(EnvironmentOperationType.SKILL_UPDATE),
            any()))
        .thenReturn(op);

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID + "/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.id").value(OP_ID.toString()))
        .andExpect(jsonPath("$.data.operationType").value("SKILL_UPDATE"));
  }

  /** 意图：验证提交操作发生并发冲突时（DuplicateActiveOperationException），返回 409 Conflict。 */
  @Test
  void submitOperationDuplicateReturnsConflict() throws Exception {
    when(operationService.create(
            eq(EnvironmentId.of(ENV_ID)),
            eq(SOURCE_ID),
            eq(EnvironmentOperationType.SKILL_REFRESH),
            any()))
        .thenThrow(
            new DuplicateActiveOperationException(
                ENV_ID, EnvironmentOperationResourceType.SKILL_SOURCE, SOURCE_ID));

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/skill-sources/" + SOURCE_ID + "/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("duplicate"));
  }

  /** 意图：验证 GET /api/harness/environments/{envId}/operations 查询操作列表返回 200 OK。 */
  @Test
  void listOperationsReturnsOk() throws Exception {
    EnvironmentOperationDTO op = new EnvironmentOperationDTO();
    op.setId(OP_ID.toString());
    when(operationService.list(eq(EnvironmentId.of(ENV_ID)), eq(50))).thenReturn(List.of(op));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/operations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(OP_ID.toString()));
  }

  /** 意图：验证 GET /api/harness/environments/{envId}/operations/{opId} 查询单个操作返回 200 OK。 */
  @Test
  void getOperationReturnsOk() throws Exception {
    EnvironmentOperationDTO op = new EnvironmentOperationDTO();
    op.setId(OP_ID.toString());
    when(operationService.get(eq(EnvironmentId.of(ENV_ID)), eq(OP_ID))).thenReturn(op);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/operations/" + OP_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(OP_ID.toString()));
  }

  /** 意图：验证 POST /api/harness/environments/{envId}/operations/{opId}/cancel 取消操作返回 200 OK。 */
  @Test
  void cancelOperationReturnsOk() throws Exception {
    EnvironmentOperationDTO op = new EnvironmentOperationDTO();
    op.setId(OP_ID.toString());
    op.setStatus("CANCELLED");
    when(operationService.cancel(eq(EnvironmentId.of(ENV_ID)), eq(OP_ID))).thenReturn(op);

    mockMvc
        .perform(post("/api/harness/environments/" + ENV_ID + "/operations/" + OP_ID + "/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }
}
