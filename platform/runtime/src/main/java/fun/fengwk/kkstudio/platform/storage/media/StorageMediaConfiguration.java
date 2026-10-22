package fun.fengwk.kkstudio.platform.storage.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobPreviewService;

/** 存储媒体增强装配：ffprobe/ffmpeg 二进制与临时目录来自部署属性，运行参数来自 SystemSettings.storageMedia。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageMediaProperties.class)
public class StorageMediaConfiguration {

  @Bean
  @Primary
  public FfmpegStorageMediaProbe ffmpegStorageMediaProbe(
      StorageMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      S3StorageService storageService,
      ObjectMapper objectMapper) {
    return new FfmpegStorageMediaProbe(properties, snapshot, storageService, objectMapper);
  }

  @Bean
  public StorageBlobPreviewService storageBlobPreviewService(
      StorageMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      S3StorageService storageService) {
    return new FfmpegStorageBlobPreviewService(properties, snapshot, storageService);
  }
}
