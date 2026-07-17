package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.share.model.S3PresignedRequestDTO;
import fun.fengwk.kkstudio.share.model.S3PresignedResponseDTO;

/**
 * S3 预签名 API：仅生成签名 URL，不与 S3 产生 IO。
 *
 * <p>bucket 与 expiry 上下界均来自服务端配置；调用方在请求体中只能指定 key、可选 contentType 与 expiresInSeconds。 仅在 {@code
 * kk-studio.storage.s3.enabled=true} 时注册，避免未启用 S3 的部署因缺 bean 而启动失败。
 *
 * @author fengwk
 */
@AllArgsConstructor
@ConditionalOnProperty(prefix = "kk-studio.storage.s3", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/s3")
public class StudioS3PresignController {

  private final S3PresignService s3PresignService;

  /** 为上传（PUT）生成预签名 URL 与调用方必须显式设置的 headers。 */
  @PostMapping("/presigned-uploads")
  public Result<S3PresignedResponseDTO> presignUpload(@RequestBody S3PresignedRequestDTO request) {
    return Results.ok(
        s3PresignService.presignUpload(
            request.getKey(), request.getContentType(), request.getExpiresInSeconds()));
  }

  /** 为下载（GET）生成预签名 URL 与调用方必须显式设置的 headers。 */
  @PostMapping("/presigned-downloads")
  public Result<S3PresignedResponseDTO> presignDownload(
      @RequestBody S3PresignedRequestDTO request) {
    return Results.ok(
        s3PresignService.presignDownload(request.getKey(), request.getExpiresInSeconds()));
  }
}
