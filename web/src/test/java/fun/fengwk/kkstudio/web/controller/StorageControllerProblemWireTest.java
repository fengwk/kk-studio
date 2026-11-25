package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.storage.http.StorageController;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;

import java.util.UUID;

class StorageControllerProblemWireTest {

  private static final StudioPrincipal ACTOR =
      new StudioPrincipal(UUID.randomUUID(), "storage-test", StudioRole.USER);

  private static final String RESERVE_BODY =
      """
      {"filename":"a.txt","mediaType":"text/plain","sizeBytes":"1",
      "sha256":"0000000000000000000000000000000000000000000000000000000000000000"}
      """;

  private final StorageUploadService uploads = mock(StorageUploadService.class);
  private final MockMvc mvc =
      standaloneSetup(new StorageController(uploads))
          .defaultRequest(post("/").requestAttr(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, ACTOR))
          .setControllerAdvice(
              new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
              new ProblemAdvice(new MessageService()))
          .setMessageConverters(
              new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
          .build();

  /** 上传校验冲突保留稳定原因，而不是走另一套错误信封。 */
  @Test
  void verificationFailureIsConflictProblem() throws Exception {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(uploads.complete(id, ACTOR.userId()))
        .thenThrow(DomainException.conflict("STORAGE_VERIFICATION", "checksum mismatch"));
    mvc.perform(post("/api/platform/storage/uploads/{uploadId}/complete", id))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.detail").value("checksum mismatch"));
  }

  /** 上传契约使用生产严格 mapper：未知字段在触达存储服务前就是 validation。 */
  @Test
  void unknownReserveFieldIsValidationBeforeServiceCall() throws Exception {
    mvc.perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"filename":"a.txt","mediaType":"text/plain","sizeBytes":"1",
                    "sha256":"0000000000000000000000000000000000000000000000000000000000000000",
                    "unknown":true}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(uploads);
  }

  /** 领域校验保留类别，未知运行时故障保持 500：HTTP 边界不再把宽泛 IllegalArgumentException 映射成 400。 */
  @Test
  void domainValidationAndUnexpectedFailuresKeepTheirCategories() throws Exception {
    when(uploads.reserve(any(), eq(ACTOR.userId())))
        .thenThrow(DomainException.validation("filename", "filename is required"))
        .thenThrow(new IllegalArgumentException("internal storage fault"));
    mvc.perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESERVE_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("filename is required"));
    mvc.perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESERVE_BODY))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal"))
        .andExpect(jsonPath("$.detail").doesNotExist());
  }
}
