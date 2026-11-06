package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.i18n.MessageService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

class ProblemAdviceTest {

  private final ProblemAdvice advice = new ProblemAdvice(new MessageService());
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(advice).build();

  /** MVC 序列化必须展平扩展字段，保留版本冲突坐标而不暴露 resource。 */
  @Test
  void versionConflictIsProblemJson() throws Exception {
    mvc.perform(get("/problem/version"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("3"))
        .andExpect(jsonPath("$.errors.actualVersion").value("4"))
        .andExpect(jsonPath("$.instance").doesNotExist())
        .andExpect(jsonPath("$.resource").doesNotExist())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  /** Canvas 冲突保留领域 reason 和结构化详情。 */
  @Test
  void conflictRetainsStructuredDetails() throws Exception {
    mvc.perform(get("/problem/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("CANVAS_CONFLICT"))
        .andExpect(jsonPath("$.detail").value("stale canvas"))
        .andExpect(jsonPath("$.errors.conflicts[0]").value("node"));
  }

  /** ResponseStatusException 的 detail 支持请求边界指出具体属性路径。 */
  @Test
  void responseStatusRetainsDetailAndMapsStatus() throws Exception {
    mvc.perform(get("/problem/required"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("body.name is required"));
    for (int status : new int[] {400, 405, 406, 413, 415, 422}) {
      assertCategory(status, "validation");
    }
    for (int status : new int[] {401, 403}) {
      assertCategory(status, "unauthorized");
    }
    assertCategory(404, "not_found");
    assertCategory(409, "conflict");
    assertCategory(502, "upstream_failed");
    assertCategory(504, "upstream_failed");
    assertCategory(503, "unavailable");
    assertCategory(418, "internal");
    DomainException conflict = DomainException.conflict("STALE_COMMAND_CURSOR", "stale cursor");
    var wrapped =
        advice.responseStatus(
            new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict));
    assertEquals("STALE_COMMAND_CURSOR", wrapped.getBody().get("reason"));
  }

  /** 缺失和非法参数给出参数名但不回显不可信输入。 */
  @Test
  void invalidParametersHaveSafeDetails() throws Exception {
    mvc.perform(get("/problem/parameter"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("limit is required"));
    mvc.perform(get("/problem/parameter").param("limit", "secret"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("limit has an invalid value"));
  }

  /** 内部认证只暴露固定 detail；不可用原因决定本地化标题。 */
  @Test
  void authenticationAndLocalizedReason() throws Exception {
    var auth = advice.authentication(new ClusterInternalAuthenticationException("secret"));
    assertEquals(401, auth.getStatusCode().value());
    assertEquals("unauthorized", auth.getBody().get("code"));
    assertEquals("internal authentication failed", auth.getBody().get("detail"));
    mvc.perform(get("/problem/unavailable").header("Accept-Language", "zh-CN"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("unavailable"))
        .andExpect(jsonPath("$.reason").value("IDE_UNAVAILABLE"))
        .andExpect(jsonPath("$.title").value("环境 IDE 当前不可用，请稍后重试。"));
  }

  /** 每类领域失败由同一处映射到固定 HTTP 状态。 */
  @Test
  void mapsDomainCategories() {
    assertEquals(
        400, advice.domain(DomainException.validation("x", "invalid")).getStatusCode().value());
    assertEquals(404, advice.domain(DomainException.notFound("x")).getStatusCode().value());
    assertEquals(
        409, advice.domain(DomainException.duplicate("x", "duplicate")).getStatusCode().value());
    assertEquals(409, advice.domain(DomainException.inUse("x", "busy")).getStatusCode().value());
    assertEquals(
        502, advice.domain(DomainException.upstream("x", "failed")).getStatusCode().value());
  }

  /** Spring 自带错误保留状态；未预期错误的标题按请求语言翻译。 */
  @Test
  void springStatusAndInternalLocalization() {
    var missing =
        advice.unexpected(new NoResourceFoundException(HttpMethod.GET, "/missing", "missing"));
    assertEquals(404, missing.getStatusCode().value());
    assertEquals("not_found", missing.getBody().get("code"));
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    try {
      var internal = advice.unexpected(new IllegalStateException("secret"));
      assertEquals("服务器发生意外错误。", internal.getBody().get("title"));
      assertNull(internal.getBody().get("detail"));
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
  }

  /** 普通 parser 异常不回显正文。 */
  @Test
  void unreadableBodyIsSanitized() {
    MockHttpInputMessage input = new MockHttpInputMessage(new byte[0]);
    Map<String, Object> ordinary =
        advice.unreadable(new HttpMessageNotReadableException("secret", input)).getBody();
    assertEquals("validation", ordinary.get("code"));
    assertEquals("Failed to read request", ordinary.get("detail"));
  }

  /** 未知异常不得泄露内部消息。 */
  @Test
  void unexpectedErrorHasNoDetail() throws Exception {
    mvc.perform(get("/problem/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal"))
        .andExpect(jsonPath("$.detail").doesNotExist());
    assertNull(advice.unexpected(new IllegalStateException("secret")).getBody().get("detail"));
  }

  private void assertCategory(int status, String code) {
    var response =
        advice.responseStatus(new ResponseStatusException(HttpStatusCode.valueOf(status)));
    assertEquals(status, response.getStatusCode().value());
    assertEquals(code, response.getBody().get("code"));
    if (status == 409) {
      assertEquals("HTTP_CONFLICT", response.getBody().get("reason"));
    }
  }

  @RestController
  static class Probe {
    @GetMapping("/problem/version")
    void version() {
      throw DomainException.versionConflict("canvas", "3", "4");
    }

    @GetMapping("/problem/conflict")
    void conflict() {
      throw DomainException.conflict(
          "CANVAS_CONFLICT", "stale canvas", Map.of("conflicts", List.of("node")));
    }

    @GetMapping("/problem/required")
    void required() {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body.name is required");
    }

    @GetMapping("/problem/parameter")
    void parameter(@RequestParam("limit") int limit) {}

    @GetMapping("/problem/unavailable")
    void unavailable() {
      throw DomainException.unavailable("IDE_UNAVAILABLE", "offline");
    }

    @GetMapping("/problem/unexpected")
    void unexpected() {
      throw new IllegalStateException("secret");
    }
  }
}
