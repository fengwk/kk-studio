package fun.fengwk.kkstudio.canvas.infra.platform;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasBlobReleaser;
import fun.fengwk.kkstudio.canvas.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

/**
 * Canvas 与平台全局 Blob 的适配装配：Function 读取冻结原件、输出物化与 Resource 行删除时的引用释放。
 *
 * <p>与 Canvas 运行时（{@code CanvasInfraAutoConfiguration}）分开装配，使 Canvas 模块测试可以只加载运行时本身。插件 Function 目录的
 * source 由 web 组合根基于插件运行时构造。
 */
@AutoConfiguration
public class CanvasPlatformAutoConfiguration {

  @Bean
  public CanvasFunctionBlobAccess canvasFunctionBlobAccess(
      S3StorageService storageService, StorageBlobManager blobManager) {
    return new PlatformCanvasFunctionBlobAccess(storageService, blobManager);
  }

  @Bean
  public CanvasResourceMaterializer canvasBlobResourceMaterializer(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      CanvasStore canvasStore,
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasResourceRepository resourceRepository,
      TransactionTemplate transactionTemplate) {
    return new CanvasBlobResourceMaterializer(
        uploadService,
        blobManager,
        canvasStore,
        runRepository,
        pinRepository,
        resourceRepository,
        transactionTemplate);
  }

  /**
   * Canvas Resource 行删除与 Blob 引用释放必须同事务：{@link StorageBlobManager#release} 是 MANDATORY 事务内的引用减一，减到
   * 0 时只把行切换为 DELETING，对象字节由 Storage 维护任务在事务外回收。无法释放时抛出异常，使外层 Canvas 事务整体回滚。
   */
  @Bean
  public CanvasBlobReleaser canvasBlobReleaser(StorageBlobManager blobManager) {
    return blobId -> {
      if (!blobManager.release(blobId)) {
        throw new IllegalStateException("release canvas resource blob failed: " + blobId);
      }
    };
  }
}
