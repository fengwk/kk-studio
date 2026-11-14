package fun.fengwk.kkstudio.plugin.comfyui;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * H3 Prompt 内容的暂存与清理边界。
 *
 * <p>顺序固定：先完成可失败的纯文本准备，再逐个打开原始流、暂存并登记句柄，最后按「manifest 表格文本 -> 每个引用 label 文本 +
 * 对应媒体片段」组织有序内容。纯文本准备不产生宿主资源，因此先行完成；一旦开始暂存，每个句柄都在产生后立即登记进本次调用的清理清单，任何后续失败（暂存失败、关闭原流失败或内容组装失败）都在同一所有权边界内清理全部已知句柄，且绝不调用
 * submit。
 */
@Slf4j
final class H3PromptStaging {

  private final H3PromptRequestBuilder promptBuilder;
  private final PluginPromptResources promptResources;

  H3PromptStaging(H3PromptRequestBuilder promptBuilder, PluginPromptResources promptResources) {
    this.promptBuilder = promptBuilder;
    this.promptResources = promptResources;
  }

  List<PluginPromptContent> prepare(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest) {
    List<H3ReferenceManifest.Item> items = manifest.items();
    PluginPromptContent table = promptBuilder.manifestTable(run, manifest);
    List<PluginPromptContent> labels = new ArrayList<>(items.size());
    for (H3ReferenceManifest.Item item : items) {
      labels.add(promptBuilder.referenceLabel(item));
    }
    List<PluginPromptResources.StagedResource> staged = new ArrayList<>(items.size());
    try {
      List<PluginPromptContent> contents = new ArrayList<>(1 + items.size() * 2);
      contents.add(table);
      for (int i = 0; i < items.size(); i++) {
        contents.add(labels.get(i));
        contents.add(new PluginPromptContent.Media(stageMedia(context, items.get(i), staged)));
      }
      return contents;
    } catch (RuntimeException | Error failure) {
      discardAll(staged, failure);
      throw failure;
    }
  }

  /** 打开原始流并暂存句柄，句柄在关闭原始流之前就登记进调用方的清理清单：即使关闭原流失败，本次已产生的句柄也不会丢失清理。 */
  private PluginPromptResources.StagedResource stageMedia(
      CanvasFunctionExecutionContext context,
      H3ReferenceManifest.Item item,
      List<PluginPromptResources.StagedResource> staged) {
    try (CanvasFunctionResourceStream stream = context.openOriginal(item.reference())) {
      PluginPromptResources.StagedResource handle =
          promptResources.stage(
              item.reference().name(),
              item.reference().mediaType(),
              stream.content(),
              stream.size());
      staged.add(handle);
      return handle;
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot close H3 reference media " + item.reference().resourceId(), error);
    }
  }

  /**
   * 尽力清理全部已知句柄：每个句柄都尝试一次，主失败的身份与类型必须原样保留。
   *
   * <p>清理期的 {@link RuntimeException} 只按去敏类型日志（上传行的耐久过期仍是兜底），绝不进入主失败的传播链；清理期的 {@link Error} 按仓库
   * primary/suppressed 约定附加到主失败后继续清理剩余句柄，既不遮蔽也不吞掉。
   */
  private void discardAll(List<PluginPromptResources.StagedResource> staged, Throwable primary) {
    for (PluginPromptResources.StagedResource handle : staged) {
      try {
        promptResources.discard(handle);
      } catch (RuntimeException cleanup) {
        log.debug("H3 staged media cleanup failed type={}", cleanup.getClass().getSimpleName());
      } catch (Error cleanup) {
        if (cleanup != primary) {
          primary.addSuppressed(cleanup);
        }
      }
    }
  }
}
