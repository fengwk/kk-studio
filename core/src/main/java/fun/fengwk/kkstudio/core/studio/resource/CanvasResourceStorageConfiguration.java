package fun.fengwk.kkstudio.core.studio.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;

import java.time.Instant;

/** Canvas Resource storage/upload 组件装配。 */
@ConditionalOnProperty(prefix = "kk-studio.storage.s3", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CanvasMediaProperties.class)
@Configuration
public class CanvasResourceStorageConfiguration {

  @Bean
  public CanvasMediaProcessor canvasMediaProcessor(
      CanvasMediaProperties properties, ObjectMapper objectMapper) {
    return new FfmpegCanvasMediaProcessor(properties, objectMapper);
  }

  @Bean
  public CanvasResourceStorageService canvasResourceStorageService(
      CanvasDocumentMapper documentMapper,
      CanvasResourceRepository resourceRepository,
      CanvasUploadRepository uploadRepository,
      PostgresqlSequenceIdGenerator idGenerator,
      S3StorageService storageService,
      S3PresignService presignService,
      CanvasMediaProcessor mediaProcessor,
      CanvasResourceCommitter committer,
      CanvasMediaProperties properties) {
    return new CanvasResourceStorageService(
        documentMapper,
        resourceRepository,
        uploadRepository,
        idGenerator,
        storageService,
        presignService,
        mediaProcessor,
        committer,
        properties,
        Instant::now);
  }
}
