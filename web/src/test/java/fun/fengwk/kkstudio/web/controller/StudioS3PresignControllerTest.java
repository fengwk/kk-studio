package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.share.storage.S3PresignedRequestDTO;
import fun.fengwk.kkstudio.web.storage.S3WebPostgresTestSupport;

/**
 * {@link StudioS3PresignController} 端到端测试.
 *
 * <p>通过 S3 测试基座把 {@code system_setting.storageMedia.s3Enabled} 置为 true 后走完整的 {@link
 * fun.fengwk.kkstudio.platform.storage.S3PresignService} 自动配置链路：使用 AWS SDK 真实 {@code
 * S3Presigner}（path-style + SigV4）在本地完成签名， 不与对象存储产生任何 IO。专注于验证 controller
 * 的请求解析、响应字段映射以及入参校验失败的传播路径。
 *
 * @author fengwk
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=AKIAIOSFODNN7EXAMPLE",
      "kk-studio.storage.s3.secret-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    })
public class StudioS3PresignControllerTest extends S3WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldReturnPresignedUploadResponse() throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey("comfyui-inputs/demo/upload/demo.bin");
    body.setContentType("application/octet-stream");
    body.setExpiresInSeconds(300L);

    mockMvc
        .perform(
            post("/api/s3/presigned-uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bucket").value("test-bucket"))
        .andExpect(jsonPath("$.data.key").value("comfyui-inputs/demo/upload/demo.bin"))
        .andExpect(jsonPath("$.data.method").value("PUT"))
        .andExpect(
            jsonPath("$.data.url")
                .value(
                    startsWith(
                        "https://cdn.example.com/test-bucket/comfyui-inputs/demo/upload/demo.bin")))
        .andExpect(jsonPath("$.data.url").value(containsString("X-Amz-Signature=")))
        .andExpect(jsonPath("$.data.headers").exists())
        .andExpect(jsonPath("$.data.expiresAt").isString())
        .andExpect(jsonPath("$.data.expiresAt").value(endsWith("Z")));
  }

  @Test
  public void shouldReturnPresignedDownloadResponse() throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey("comfyui-inputs/demo/download/report.pdf");
    body.setExpiresInSeconds(120L);

    mockMvc
        .perform(
            post("/api/s3/presigned-downloads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bucket").value("test-bucket"))
        .andExpect(jsonPath("$.data.key").value("comfyui-inputs/demo/download/report.pdf"))
        .andExpect(jsonPath("$.data.method").value("GET"))
        .andExpect(
            jsonPath("$.data.url")
                .value(
                    startsWith(
                        "https://cdn.example.com/test-bucket/comfyui-inputs/demo/download/report.pdf")))
        .andExpect(jsonPath("$.data.url").value(containsString("X-Amz-Signature=")));
  }

  /** 通用预签名端点只能访问 ComfyUI 临时输入，不能触达 Canvas 或其它对象命名空间。 */
  @Test
  public void shouldRejectKeysOutsideComfyuiInputsNamespace() throws Exception {
    assertRejectedKey("/api/s3/presigned-uploads", "canvases/1/resources/2/original");
    assertRejectedKey("/api/s3/presigned-downloads", "canvases/1/resources/2/preview.webp");
    assertRejectedKey("/api/s3/presigned-uploads", "uploads/demo.bin");
    assertRejectedKey("/api/s3/presigned-downloads", "comfyui-inputs/");
    assertRejectedKey("/api/s3/presigned-uploads", "comfyui-inputs/   ");
    assertRejectedKey("/api/s3/presigned-downloads", "comfyui-inputs///");
  }

  /** 校验失败的 key（如空白）必须原样抛回 4xx，而不能被 controller 吞掉。 */
  @Test
  public void shouldRejectBlankKey() throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey("   ");

    mockMvc
        .perform(
            post("/api/s3/presigned-uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());
  }

  /** 校验失败的 key（如包含 .. 段）必须原样抛回 4xx。 */
  @Test
  public void shouldRejectPathTraversalKey() throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey("../etc/passwd");

    mockMvc
        .perform(
            post("/api/s3/presigned-downloads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());
  }

  /** 边界：expiresInSeconds 不传时使用服务端默认（600s），校验响应 expiresAt 字段格式正确。 */
  @Test
  public void shouldUseDefaultExpiresWhenNotProvided() throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey("comfyui-inputs/demo/default/readme.md");

    String responseBody =
        mockMvc
            .perform(
                post("/api/s3/presigned-downloads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNotNull(responseBody);
    assertTrue(responseBody.contains("\"expiresAt\""), "expiresAt field must be present");
    assertTrue(
        responseBody.contains("X-Amz-Expires=600"),
        "default expiry 600s should appear in signed URL, response: " + responseBody);
  }

  /** 超过服务端最大有效期必须返回 4xx 参数错误。 */
  @Test
  public void shouldRejectExpiresOverMax() throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey("comfyui-inputs/demo/expires/file.bin");
    body.setExpiresInSeconds(86_400L);

    mockMvc
        .perform(
            post("/api/s3/presigned-downloads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  private void assertRejectedKey(String endpoint, String key) throws Exception {
    S3PresignedRequestDTO body = new S3PresignedRequestDTO();
    body.setKey(key);
    mockMvc
        .perform(
            post(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }
}
