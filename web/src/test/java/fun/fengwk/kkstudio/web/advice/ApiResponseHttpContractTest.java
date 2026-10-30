package fun.fengwk.kkstudio.web.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 通过真实 MVC 响应链锁定 API 信封的 HTTP 状态，而不是只直接调用 advice 方法。 */
class ApiResponseHttpContractTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiResponseStatusAdvice(), new ProbeErrorAdvice())
            .build();
  }

  @Test
  void directCreatedEnvelopeProducesHttp201AndSuccessWireFlag() throws Exception {
    mockMvc
        .perform(get("/probe/created"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value(201))
        .andExpect(jsonPath("$.code").value("CREATED"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").value("created"));
  }

  @Test
  void directNoContentEnvelopeProducesHttp204() throws Exception {
    mockMvc.perform(get("/probe/no-content")).andExpect(status().isNoContent());
  }

  @Test
  void responseEntityApiResponseKeepsItsExplicitStatus() throws Exception {
    mockMvc
        .perform(get("/probe/entity-created"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value(201))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").value("entity-created"));
  }

  @Test
  void responseEntityApiResponseKeepsExplicitNoContentStatus() throws Exception {
    mockMvc.perform(get("/probe/entity-no-content")).andExpect(status().isNoContent());
  }

  @Test
  void responseEntityApiResponseKeepsExplicitErrorStatus() throws Exception {
    mockMvc
        .perform(get("/probe/entity-error"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.code").value("PROBE_UNPROCESSABLE"))
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void errorAdviceResponseEntityIsNotRewrittenByStatusAdvice() throws Exception {
    mockMvc
        .perform(get("/probe/fail"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("PROBE_INVALID"))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errors.detail").value("probe rejected"));
  }

  @Test
  void directErrorEnvelopeProducesItsHttpStatus() throws Exception {
    mockMvc
        .perform(get("/probe/error"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void rawResponseIsNotWrappedOrRewritten() throws Exception {
    mockMvc
        .perform(get("/probe/raw"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string("raw"));
  }

  @Test
  void htmlResponseIsNotWrappedOrRewritten() throws Exception {
    mockMvc
        .perform(get("/probe/html"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string("<html><body>raw</body></html>"));
  }

  @Test
  void fileResourceResponseIsNotWrappedOrRewritten() throws Exception {
    mockMvc
        .perform(get("/probe/file"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
        .andExpect(content().bytes("file".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void streamingResponseIsNotWrappedOrRewritten() throws Exception {
    MvcResult pending =
        mockMvc.perform(get("/probe/stream")).andExpect(request().asyncStarted()).andReturn();

    mockMvc
        .perform(asyncDispatch(pending))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
        .andExpect(content().bytes("stream".getBytes(StandardCharsets.UTF_8)));
  }

  @RestController
  static class ProbeController {

    @GetMapping("/probe/created")
    ApiResponse<String> created() {
      return ApiResponse.created("created");
    }

    @GetMapping("/probe/no-content")
    ApiResponse<Void> noContent() {
      return ApiResponse.noContent();
    }

    @GetMapping("/probe/entity-created")
    ResponseEntity<ApiResponse<String>> entityCreated() {
      return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("entity-created"));
    }

    @GetMapping("/probe/entity-no-content")
    ResponseEntity<ApiResponse<Void>> entityNoContent() {
      return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }

    @GetMapping("/probe/entity-error")
    ResponseEntity<ApiResponse<Void>> entityError() {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(
              ApiResponse.error(
                  HttpStatus.UNPROCESSABLE_ENTITY,
                  "PROBE_UNPROCESSABLE",
                  "probe cannot be processed",
                  Map.of("detail", "probe rejected")));
    }

    @GetMapping("/probe/fail")
    String fail() {
      throw new ProbeException();
    }

    @GetMapping("/probe/error")
    ApiResponse<Void> error() {
      return ApiResponse.error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PROBE_UNPROCESSABLE",
          "probe cannot be processed",
          Map.of("detail", "probe rejected"));
    }

    @GetMapping(value = "/probe/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    String raw() {
      return "raw";
    }

    @GetMapping(value = "/probe/html", produces = MediaType.TEXT_HTML_VALUE)
    String html() {
      return "<html><body>raw</body></html>";
    }

    @GetMapping("/probe/file")
    ResponseEntity<Resource> file() {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .body(new ByteArrayResource("file".getBytes(StandardCharsets.UTF_8)));
    }

    @GetMapping(value = "/probe/stream", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> stream() {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .body(output -> output.write("stream".getBytes(StandardCharsets.UTF_8)));
    }
  }

  static final class ProbeException extends RuntimeException {}

  @RestControllerAdvice(assignableTypes = ProbeController.class)
  static class ProbeErrorAdvice {

    @ExceptionHandler(ProbeException.class)
    ResponseEntity<ApiResponse<Void>> handle(ProbeException error) {
      return ResponseEntity.badRequest()
          .body(
              ApiResponse.error(
                  HttpStatus.BAD_REQUEST,
                  "PROBE_INVALID",
                  "probe is invalid",
                  Map.of("detail", "probe rejected")));
    }
  }
}
