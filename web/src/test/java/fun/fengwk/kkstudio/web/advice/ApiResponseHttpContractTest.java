package fun.fengwk.kkstudio.web.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import fun.fengwk.kkstudio.platform.http.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 通过真实 MVC 响应链锁定过渡期成功响应的状态和资源体。 */
class ApiResponseHttpContractTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiResponseStatusAdvice())
            .build();
  }

  @Test
  void directCreatedEnvelopeProducesHttp201AndResource() throws Exception {
    mockMvc
        .perform(get("/probe/created"))
        .andExpect(status().isCreated())
        .andExpect(content().json("\"created\""));
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
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("\"entity-created\""));
  }

  @Test
  void responseEntityApiResponseKeepsExplicitNoContentStatus() throws Exception {
    mockMvc.perform(get("/probe/entity-no-content")).andExpect(status().isNoContent());
  }

  @Test
  void legacyErrorEnvelopeIsNotDiscardedDuringMigration() throws Exception {
    mockMvc
        .perform(get("/probe/entity-error"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.detail").value("rejected"));
    mockMvc
        .perform(get("/probe/direct-error"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.errors.detail").value("rejected"));
  }

  @Test
  void nullDataUses204For200AndPreserves202() throws Exception {
    mockMvc
        .perform(get("/probe/empty"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    mockMvc
        .perform(get("/probe/accepted"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));
    mockMvc
        .perform(get("/probe/entity-empty"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
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
      return ResponseEntity.status(HttpStatus.CREATED)
          .header("Cache-Control", "no-store")
          .body(ApiResponse.created("entity-created"));
    }

    @GetMapping("/probe/entity-no-content")
    ResponseEntity<ApiResponse<Void>> entityNoContent() {
      return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }

    @GetMapping("/probe/entity-error")
    ResponseEntity<ApiResponse<Void>> entityError() {
      return ResponseEntity.badRequest()
          .body(
              ApiResponse.error(
                  HttpStatus.BAD_REQUEST, "validation", "invalid", Map.of("detail", "rejected")));
    }

    @GetMapping("/probe/direct-error")
    ApiResponse<Void> directError() {
      return ApiResponse.error(
          HttpStatus.CONFLICT, "conflict", "conflict", Map.of("detail", "rejected"));
    }

    @GetMapping("/probe/empty")
    ApiResponse<Void> empty() {
      return ApiResponse.ok();
    }

    @GetMapping("/probe/entity-empty")
    ResponseEntity<ApiResponse<Void>> entityEmpty() {
      return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/probe/accepted")
    ApiResponse<Void> accepted() {
      return ApiResponse.accepted();
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
}
