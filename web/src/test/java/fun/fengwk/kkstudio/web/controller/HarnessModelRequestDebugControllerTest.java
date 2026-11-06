package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;

import java.time.Instant;
import java.util.List;

/** Thread 的 Model Request Debug 只读投影端点。 */
class HarnessModelRequestDebugControllerTest extends HarnessThreadControllerTestSupport {

  /**
   * 意图：POST /api/harness/builtin/threads/{threadId}/model-request-debug 以请求体草稿 model/environment
   * 只读转发到结构化 Debug 服务并按 DTO 契约序列化（required-nullable 字段显式发射 null），且绝不触碰
   * HarnessRuntime（无任何写入/控制面副作用）。
   */
  @Test
  void modelRequestDebugReturnsStructuredPreviewWithoutTouchingRuntime() throws Exception {
    ModelSelection draftModel = new ModelSelection("draft-model", "draft-variant");
    when(modelRequestDebugService.getModelRequestDebug(id(1), draftModel, "env-c"))
        .thenReturn(sampleDebug());

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/" + idText(1) + "/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"model\":{\"modelName\":\"draft-model\","
                        + "\"variant\":\"draft-variant\"},\"environmentName\":\"env-c\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("NEXT_REQUEST_PREVIEW"))
        .andExpect(jsonPath("$.model.modelName").value("model"))
        .andExpect(jsonPath("$.model.variant").value("default"))
        .andExpect(jsonPath("$.environmentName").value(nullValue()))
        .andExpect(jsonPath("$.systemInstruction").value("system instruction"))
        .andExpect(jsonPath("$.tools[0].name").value("read"))
        .andExpect(jsonPath("$.tools[0].state").value("SENT"))
        .andExpect(jsonPath("$.tools[0].environmentSupport").value("ROUTED"))
        .andExpect(jsonPath("$.tools[0].filterReason").value(nullValue()))
        .andExpect(jsonPath("$.tools[1].name").value("bash"))
        .andExpect(jsonPath("$.tools[1].state").value("FILTERED"))
        .andExpect(jsonPath("$.tools[1].filterReason").value("ENVIRONMENT_NOT_SELECTED"))
        .andExpect(jsonPath("$.skills[0].delivery").value("PLATFORM"))
        .andExpect(jsonPath("$.skills[0].headCommit").value(nullValue()))
        .andExpect(jsonPath("$.subagents[0].name").value("coder"))
        .andExpect(jsonPath("$.subagents[0].tools[0]").value("read"))
        .andExpect(jsonPath("$.subagents[0].skills[0].packageName").value("pkg"))
        .andExpect(jsonPath("$.subagents[0].subagents[0]").value("nested"))
        .andExpect(jsonPath("$.subagents[0].configuration.tools[0]").value("read"))
        .andExpect(jsonPath("$.tools[0].inputSchema.type").value("object"))
        .andExpect(jsonPath("$.cacheControl.retention").value("NONE"))
        .andExpect(jsonPath("$.cacheControl.key").value(nullValue()))
        .andExpect(jsonPath("$.cacheControl.affinityKey").doesNotExist())
        .andExpect(jsonPath("$.planningError").value(nullValue()))
        .andExpect(jsonPath("$.frozenInvocation").value(nullValue()));

    // 活动 Invocation 的冻结视图原样透传：kind 与 canonical request JSON 按字符串承载。
    HarnessModelRequestDebugDTO frozen = sampleDebug();
    HarnessModelRequestDebugDTO.FrozenInvocationDTO frozenInvocation =
        new HarnessModelRequestDebugDTO.FrozenInvocationDTO();
    frozenInvocation.setKind(
        HarnessModelRequestDebugDTO.FrozenInvocationDTO.FrozenKind.FROZEN_INVOCATION);
    frozenInvocation.setRequestJson("{\"model\":\"wire-model\"}");
    frozen.setFrozenInvocation(frozenInvocation);
    when(modelRequestDebugService.getModelRequestDebug(id(1), draftModel, "env-c"))
        .thenReturn(frozen);

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/" + idText(1) + "/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"model\":{\"modelName\":\"draft-model\","
                        + "\"variant\":\"draft-variant\"},\"environmentName\":\"env-c\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.frozenInvocation.kind").value("FROZEN_INVOCATION"))
        .andExpect(jsonPath("$.frozenInvocation.requestJson").value("{\"model\":\"wire-model\"}"));

    verify(modelRequestDebugService, times(2)).getModelRequestDebug(id(1), draftModel, "env-c");
    // Debug 是纯查询：Controller 不得调用任何 Runtime 控制面方法。
    verifyNoInteractions(runtime);
  }

  /** 意图：缺省 environmentName 表示未选择 Environment，服务收到显式 null；模型选择仍走 canonical 校验。 */
  @Test
  void modelRequestDebugForwardsMissingEnvironmentAsNull() throws Exception {
    ModelSelection draftModel = new ModelSelection("model", "default");
    when(modelRequestDebugService.getModelRequestDebug(id(1), draftModel, null))
        .thenReturn(sampleDebug());

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/" + idText(1) + "/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":{\"modelName\":\"model\"," + "\"variant\":\"default\"}}"))
        .andExpect(status().isOk());

    verify(modelRequestDebugService).getModelRequestDebug(id(1), draftModel, null);
    verifyNoInteractions(runtime);
  }

  /** 意图：非法 model（空白字段）/非法 environment（含 '/'）与未知字段都在到达 Debug 服务前以 400 拒绝。 */
  @Test
  void modelRequestDebugRejectsInvalidBodyBeforeReachingTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/harness/builtin/threads/" + idText(1) + "/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":{\"modelName\":\"," + "\"variant\":\"default\"}}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/" + idText(1) + "/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"model\":{\"modelName\":\"model\","
                        + "\"variant\":\"default\"},\"environmentName\":\"bad/name\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/" + idText(1) + "/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"model\":{\"modelName\":\"model\","
                        + "\"variant\":\"default\"},\"bogus\":1}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(modelRequestDebugService);
    verifyNoInteractions(runtime);
  }

  /** 意图：非法 threadId 在到达 Debug 服务前以 400 拒绝，不产生任何查询副作用。 */
  @Test
  void modelRequestDebugRejectsMalformedThreadId() throws Exception {
    mockMvc
        .perform(
            post("/api/harness/builtin/threads/not-a-uuid/model-request-debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":{\"modelName\":\"model\"," + "\"variant\":\"default\"}}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(modelRequestDebugService);
    verifyNoInteractions(runtime);
  }

  /** 最小合法 Debug 投影：覆盖 required-nullable 与 SENT 在前、FILTERED 在后的候选顺序。 */
  private static HarnessModelRequestDebugDTO sampleDebug() {
    HarnessModelRequestDebugDTO.ToolDTO sent = new HarnessModelRequestDebugDTO.ToolDTO();
    sent.setName("read");
    sent.setDescription("Read a file.");
    sent.setInputSchema(JsonValues.readTree("{\"type\":\"object\"}"));
    sent.setEnvironmentSupport(EnvironmentSupport.ROUTED);
    sent.setProvenance("builtin:read");
    sent.setState(LiveTurnPlan.ToolState.SENT);
    sent.setFilterReason(null);

    HarnessModelRequestDebugDTO.ToolDTO filtered = new HarnessModelRequestDebugDTO.ToolDTO();
    filtered.setName("bash");
    filtered.setDescription("Run a command.");
    filtered.setInputSchema(JsonValues.readTree("{\"type\":\"object\"}"));
    filtered.setEnvironmentSupport(EnvironmentSupport.REQUIRED);
    filtered.setProvenance("builtin:bash");
    filtered.setState(LiveTurnPlan.ToolState.FILTERED);
    filtered.setFilterReason(LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED);

    HarnessModelRequestDebugDTO.SkillDTO skill = new HarnessModelRequestDebugDTO.SkillDTO();
    skill.setPackageName("test-package");
    skill.setName("dev");
    skill.setDescription("dev description");
    skill.setPath("vfs:/skills/test-package/dev/SKILL.md");
    skill.setDelivery(SkillPromptResolution.Delivery.PLATFORM);
    skill.setPackageVersion("0123456789abcdef0123456789abcdef01234567");
    skill.setHeadCommit(null);
    skill.setArtifactSha256("a".repeat(64));
    skill.setInstalledSha256(null);
    skill.setPromptXml("  <skill>");

    HarnessModelRequestDebugDTO.SubagentDTO subagent =
        new HarnessModelRequestDebugDTO.SubagentDTO();
    subagent.setName("coder");
    subagent.setDescription("Codes solutions.");
    subagent.setTools(List.of("read"));
    SkillRefDTO skillReference = new SkillRefDTO();
    skillReference.setPackageName("pkg");
    skillReference.setName("sk");
    subagent.setSkills(List.of(skillReference));
    subagent.setSubagents(List.of("nested"));
    subagent.setConfiguration(JsonValues.readTree("{\"tools\":[\"read\"]}"));

    HarnessModelRequestDebugDTO.CacheControlDTO cacheControl =
        new HarnessModelRequestDebugDTO.CacheControlDTO();
    cacheControl.setRetention(PromptCacheRetention.NONE);
    // NONE 时 key 显式 null：wire 上「未启用」与「有 key」必须可区分。
    cacheControl.setKey(null);

    HarnessModelRequestDebugDTO debug = new HarnessModelRequestDebugDTO();
    debug.setKind(HarnessModelRequestDebugDTO.Kind.NEXT_REQUEST_PREVIEW);
    debug.setGeneratedAt(Instant.parse("2026-08-17T00:00:00Z"));
    HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
    model.setModelName("model");
    model.setVariant("default");
    debug.setModel(model);
    debug.setEnvironmentName(null);
    debug.setSystemInstruction("system instruction");
    debug.setTools(List.of(sent, filtered));
    debug.setSkills(List.of(skill));
    debug.setSubagents(List.of(subagent));
    debug.setCacheControl(cacheControl);
    debug.setPlanningError(null);
    debug.setFrozenInvocation(null);
    return debug;
  }
}
