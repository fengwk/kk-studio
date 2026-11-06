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
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.http.StorageController;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.util.UUID;

class StudioStorageProblemWireTest {

  private final StorageUploadService uploads = mock(StorageUploadService.class);
  private final StorageBlobManager blobs = mock(StorageBlobManager.class);
  private final MockMvc mvc =
      standaloneSetup(new StorageController(uploads, blobs))
          .setControllerAdvice(new StudioProblemAdvice(new StudioMessageService()))
          .build();

  /** 上传校验异常保留冲突语义和稳定原因，而不是走另一套错误信封。 */
  @Test
  void verificationFailureIsConflictProblem() throws Exception {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(uploads.complete(id)).thenThrow(new StorageVerificationException("checksum mismatch"));
    mvc.perform(post("/api/platform/storage/uploads/{uploadId}/complete", id))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.detail").value("checksum mismatch"));
  }

  /** 输入错误与其他领域异常各自保留类别，不吞掉已有的 not-found。 */
  @Test
  void invalidInputAndDomainFailureRetainTheirCategories() throws Exception {
    when(uploads.reserve(any())).thenThrow(new IllegalArgumentException("filename is required"));
    mvc.perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("filename is required"));
    UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(blobs.presignOriginalUrl(id)).thenThrow(DomainException.notFound("blob"));
    mvc.perform(post("/api/platform/storage/blobs/{blobId}/download-url", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }
}
