package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.storage.http.StorageController;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;

import java.util.UUID;

class StorageControllerProblemWireTest {

  private final StorageUploadService uploads = mock(StorageUploadService.class);
  private final StorageBlobManager blobs = mock(StorageBlobManager.class);
  private final MockMvc mvc =
      standaloneSetup(new StorageController(uploads, blobs))
          .setControllerAdvice(new ProblemAdvice(new MessageService()))
          .build();

  /** 上传校验冲突保留稳定原因，而不是走另一套错误信封。 */
  @Test
  void verificationFailureIsConflictProblem() throws Exception {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(uploads.complete(id))
        .thenThrow(DomainException.conflict("STORAGE_VERIFICATION", "checksum mismatch"));
    mvc.perform(post("/api/platform/storage/uploads/{uploadId}/complete", id))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.detail").value("checksum mismatch"));
  }

  /** 领域校验保留类别，未知运行时故障保持 500：HTTP 边界不再把宽泛 IllegalArgumentException 映射成 400。 */
  @Test
  void domainValidationAndUnexpectedFailuresKeepTheirCategories() throws Exception {
    when(uploads.reserve(any()))
        .thenThrow(DomainException.validation("filename", "filename is required"))
        .thenThrow(new IllegalArgumentException("internal storage fault"));
    mvc.perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("filename is required"));
    mvc.perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal"))
        .andExpect(jsonPath("$.detail").doesNotExist());
    UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(blobs.presignOriginalUrl(id)).thenThrow(DomainException.notFound("blob"));
    mvc.perform(post("/api/platform/storage/blobs/{blobId}/download-url", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }
}
