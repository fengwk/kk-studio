package fun.fengwk.kkstudio.web.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 公开 JSON 请求体的传输上限：超限的合法 JSON 在任何解析之前失败为 413，未超限的请求照常处理。
 *
 * <p>上限按线上字节数判定，与请求体内容无关；错误经既有 {@link ProblemAdvice} 输出 problem+json，类别仍为 {@code validation}。
 */
class RequestBodySizeLimitTest {

  private static final int LIMIT = RequestBodyRequiredValidationAdvice.MAX_BODY_BYTES;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TestProbeController())
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
                new ProblemAdvice(new MessageService()))
            .build();
  }

  /** 内容完全合法且无害，超限的唯一后果是按字节数拒绝，且不回显请求体。 */
  @Test
  void validJsonAboveLimitIsRejectedBeforeParsing() throws Exception {
    mockMvc
        .perform(
            post("/test/text").contentType(MediaType.APPLICATION_JSON).content(body(LIMIT + 1)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(413))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("request body is too large"));
  }

  /** 边界含上限本身：恰好等于上限的请求仍被接受，不引入 off-by-one 拒绝。 */
  @Test
  void bodyExactlyAtLimitIsAccepted() throws Exception {
    mockMvc
        .perform(post("/test/text").contentType(MediaType.APPLICATION_JSON).content(body(LIMIT)))
        .andExpect(status().isOk());
  }

  /** 普通大小的请求不受上限影响。 */
  @Test
  void smallBodyStillValidatesAndSucceeds() throws Exception {
    mockMvc
        .perform(
            post("/test/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/test/text").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  /** 生成恰好 {@code size} 个 UTF-8 字节的合法 JSON 文档。 */
  private static byte[] body(int size) {
    byte[] prefix = "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
    byte[] body = Arrays.copyOf(prefix, size);
    Arrays.fill(body, prefix.length, size, (byte) ' ');
    return body;
  }

  @RestController
  static final class TestProbeController {

    @PostMapping("/test/text")
    public void text(@RequestBody TextDTO body) {}
  }

  record TextDTO(String text) {}
}
