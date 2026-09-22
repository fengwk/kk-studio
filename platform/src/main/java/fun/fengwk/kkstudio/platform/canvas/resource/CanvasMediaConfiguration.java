package fun.fengwk.kkstudio.platform.canvas.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

/** Canvas 媒体集成装配：ffprobe/ffmpeg 二进制与临时目录来自部署属性，运行参数来自 SystemSettings.storageMedia。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanvasMediaProperties.class)
public class CanvasMediaConfiguration {

  @Bean
  @Primary
  public FfmpegStorageMediaProbe ffmpegStorageMediaProbe(
      CanvasMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      S3StorageService storageService,
      ObjectMapper objectMapper) {
    return new FfmpegStorageMediaProbe(properties, snapshot, storageService, objectMapper);
  }

  @Bean
  public CanvasBlobPreviewService canvasBlobPreviewService(
      CanvasMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      S3StorageService storageService) {
    return new CanvasBlobPreviewService(properties, snapshot, storageService);
  }

  @Bean
  public CanvasResourceMaterializer canvasBlobResourceMaterializer(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      CanvasStore canvasStore,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasResourceRepository resourceRepository,
      CanvasBlobPreviewService previewService,
      TransactionTemplate transactionTemplate) {
    return new CanvasBlobResourceMaterializer(
        uploadService,
        blobManager,
        canvasStore,
        pinRepository,
        resourceRepository,
        previewService,
        transactionTemplate);
  }
}
