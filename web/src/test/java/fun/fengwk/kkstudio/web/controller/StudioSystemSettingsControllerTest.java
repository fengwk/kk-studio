package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.function.Consumer;

/**
 * {@link StudioSystemSettingsController} 的端到端测试。
 *
 * <p>验证：GET 返回安全默认聚合（version="0"）；PUT 以 expectedVersion CAS 完整替换并返回新版本；陈旧版本 → 409 （version_conflict
 * 错误码）；非法聚合、未知字段与缺失 expectedVersion → 400。
 */
@AutoConfigureMockMvc
public class StudioSystemSettingsControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void getReturnsSafeDefaultAggregateWithVersionZero() throws Exception {
    mockMvc
        .perform(get("/api/settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("0"))
        .andExpect(jsonPath("$.data.tool.defaultYolo").value(false))
        .andExpect(jsonPath("$.data.tool.permission.bash[0].pattern").value("*"))
        .andExpect(jsonPath("$.data.tool.permission.bash[0].action").value("ask"))
        .andExpect(jsonPath("$.data.aiRuntime.retryBackoffStrategy").value("EXPONENTIAL"))
        .andExpect(jsonPath("$.data.aiRuntime.retryMaxRetries").value(3))
        // 仓库/Web 约定：Long 字段（时长/字节）在 wire 上输出十进制字符串；Integer 字段为数值。
        .andExpect(jsonPath("$.data.environment.maxResourceBytes").value("8388608"))
        .andExpect(jsonPath("$.data.environment.maxResourceBytes").isString())
        .andExpect(
            jsonPath("$.data.integrations.openCliHub.baseUrl").value("http://vps-opencli-hub:8080"))
        .andExpect(jsonPath("$.data.storageMedia.s3Enabled").value(false))
        .andExpect(jsonPath("$.data.advanced.dispatcherMaxDispatchTasks").value(64))
        .andExpect(jsonPath("$.data.createTime").exists())
        .andExpect(jsonPath("$.data.updateTime").exists());
  }

  @Test
  public void putReplacesTheCompleteAggregateWithCas() throws Exception {
    String first =
        bodyFromGet(
            body -> {
              body.put("expectedVersion", "0");
              body.with("tool").put("defaultYolo", true);
            });
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("1"))
        .andExpect(jsonPath("$.data.tool.defaultYolo").value(true));

    // 再次 PUT 必须使用最新版本。
    String second =
        bodyFromGet(
            body -> {
              body.put("expectedVersion", "1");
              body.with("tool").put("defaultYolo", false);
            });
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("2"))
        .andExpect(jsonPath("$.data.tool.defaultYolo").value(false));
  }

  @Test
  public void putWithStaleExpectedVersionReturns409() throws Exception {
    String body =
        bodyFromGet(
            update -> {
              update.put("expectedVersion", "99");
              update.with("tool").put("defaultYolo", true);
            });
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("99"))
        .andExpect(jsonPath("$.errors.actualVersion").value("0"));
  }

  @Test
  public void putWithMalformedVersionReturns400() throws Exception {
    String body =
        bodyFromGet(
            update -> {
              update.put("expectedVersion", "not-a-version");
            });
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  @Test
  public void putWithInvalidAggregateReturns400() throws Exception {
    // processor heartbeat 不得 >= lease：跨字段校验在持久化前拒绝。
    String body =
        bodyFromGet(
            update -> {
              update.put("expectedVersion", "0");
              update.with("advanced").put("processorHeartbeatIntervalMillis", 60_000L);
            });
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  @Test
  public void putWithUnknownFieldReturns400() throws Exception {
    String body =
        bodyFromGet(
            update -> {
              update.put("expectedVersion", "0");
              update.put("bogusSection", true);
            });
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void putWithoutExpectedVersionReturns400() throws Exception {
    String body = bodyFromGet(update -> {});
    mockMvc
        .perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  private String bodyFromGet(Consumer<ObjectNode> mutator) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/settings")).andExpect(status().isOk()).andReturn();
    JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    assertNotNull(data, "GET must return a data envelope");
    ObjectNode body = ((ObjectNode) data).deepCopy();
    // version / createTime / updateTime 是只读输出，写请求 DTO 没有这些字段，必须先剔除。
    body.remove("version");
    body.remove("createTime");
    body.remove("updateTime");
    mutator.accept(body);
    return objectMapper.writeValueAsString(body);
  }
}
