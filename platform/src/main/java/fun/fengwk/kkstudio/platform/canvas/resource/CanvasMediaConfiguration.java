package fun.fengwk.kkstudio.platform.canvas.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.mapper.StorageBlobMapper;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;

import java.util.Objects;

/** Canvas 媒体集成装配：ffprobe/ffmpeg 二进制与临时目录来自部署属性，运行参数来自 SystemSettings.storageMedia。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanvasMediaProperties.class)
public class CanvasMediaConfiguration {

  @Bean
  @Primary
  @ConditionalOnMissingBean(FfmpegStorageMediaProbe.class)
  public FfmpegStorageMediaProbe ffmpegStorageMediaProbe(
      CanvasMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      ObjectProvider<S3StorageService> storageService,
      ObjectMapper objectMapper) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new FfmpegStorageMediaProbe(
        properties,
        snapshot,
        Objects.requireNonNull(storageService.getIfAvailable(), "storageService"),
        objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(CanvasBlobPreviewService.class)
  public CanvasBlobPreviewService canvasBlobPreviewService(
      CanvasMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      ObjectProvider<S3StorageService> storageService) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new CanvasBlobPreviewService(
        properties,
        snapshot,
        Objects.requireNonNull(storageService.getIfAvailable(), "storageService"));
  }

  @Bean
  @ConditionalOnMissingBean(CanvasResourceMaterializer.class)
  public CanvasResourceMaterializer canvasBlobResourceMaterializer(
      CanvasMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      ObjectProvider<S3StorageService> storageService,
      ObjectProvider<StorageMediaProbe> mediaProbe,
      ObjectProvider<StorageBlobManager> blobManager,
      StorageBlobMapper blobMapper,
      CanvasStore canvasStore,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasResourceRepository resourceRepository,
      ObjectProvider<CanvasBlobPreviewService> previewService,
      TransactionTemplate transactionTemplate) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new CanvasBlobResourceMaterializer(
        Objects.requireNonNull(storageService.getIfAvailable(), "storageService"),
        Objects.requireNonNull(mediaProbe.getIfAvailable(), "mediaProbe"),
        Objects.requireNonNull(blobManager.getIfAvailable(), "blobManager"),
        blobMapper,
        canvasStore,
        pinRepository,
        resourceRepository,
        Objects.requireNonNull(previewService.getIfAvailable(), "previewService"),
        properties,
        transactionTemplate);
  }

  private static boolean s3Enabled(SystemSettingsSnapshot snapshot) {
    return Objects.requireNonNull(snapshot, "snapshot").get().storageMedia().s3Enabled();
  }
}
