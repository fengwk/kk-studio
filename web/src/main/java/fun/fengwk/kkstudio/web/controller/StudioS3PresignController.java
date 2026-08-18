package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.storage.S3ObjectKeyNormalizer;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.share.storage.S3PresignedRequestDTO;
import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;

/**
 * ComfyUI 临时输入的 S3 预签名 API：仅生成签名 URL，不与 S3 产生 IO。
 *
 * <p>bucket 与 expiry 上下界均来自服务端配置；调用方只能指定 {@code comfyui-inputs/} 下的对象 key、可选 contentType 与
 * expiresInSeconds。控制器总是注册；S3 未启用（SystemSettings.storageMedia.s3Enabled=false）时请求确定性返回 503。
 *
 * @author fengwk
 */
@RestController
@RequestMapping("/api/s3")
public class StudioS3PresignController {

  private static final String COMFYUI_INPUT_PREFIX = "comfyui-inputs/";

  private final ObjectProvider<S3PresignService> presignServices;

  public StudioS3PresignController(ObjectProvider<S3PresignService> presignServices) {
    this.presignServices = presignServices;
  }

  /** 为上传（PUT）生成预签名 URL 与调用方必须显式设置的 headers。 */
  @PostMapping("/presigned-uploads")
  public Result<S3PresignedResponseDTO> presignUpload(@RequestBody S3PresignedRequestDTO request) {
    return Results.ok(
        requirePresignService()
            .presignUpload(
                normalizeComfyuiInputKey(request.getKey()),
                request.getContentType(),
                request.getExpiresInSeconds()));
  }

  /** 为下载（GET）生成预签名 URL 与调用方必须显式设置的 headers。 */
  @PostMapping("/presigned-downloads")
  public Result<S3PresignedResponseDTO> presignDownload(
      @RequestBody S3PresignedRequestDTO request) {
    return Results.ok(
        requirePresignService()
            .presignDownload(
                normalizeComfyuiInputKey(request.getKey()), request.getExpiresInSeconds()));
  }

  private S3PresignService requirePresignService() {
    S3PresignService presignService = presignServices.getIfAvailable();
    if (presignService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "global blob storage is disabled");
    }
    return presignService;
  }

  private static String normalizeComfyuiInputKey(String key) {
    String normalized = S3ObjectKeyNormalizer.normalize(key);
    Assert.isTrue(
        normalized.startsWith(COMFYUI_INPUT_PREFIX),
        "key must identify an object under comfyui-inputs/");
    String suffix = normalized.substring(COMFYUI_INPUT_PREFIX.length());
    Assert.hasText(suffix, "key must identify an object under comfyui-inputs/");
    Assert.isTrue(
        suffix.chars().anyMatch(character -> character != '/'),
        "key must identify an object under comfyui-inputs/");
    return normalized;
  }
}
