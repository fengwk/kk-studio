package fun.fengwk.kkstudio.platform.plugin.prompt;

import java.util.Objects;

/**
 * 一次 Prompt 任务提交中的一个有序内容片段。
 *
 * <p>顺序即最终 USER 消息的内容顺序。插件负责 manifest、label 文本与媒体的邻接关系；宿主不认识任何模型、label 或工作流语义，只按位置接收内容。
 *
 * <p>只使用 JDK 类型：不暴露宿主消息、上传行或 Blob 结构。
 */
public sealed interface PluginPromptContent {

  /** 普通文本片段：非 null，允许空白片段，消息整体继续遵守运行时 admission。 */
  record Text(String text) implements PluginPromptContent {

    public Text {
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * 已暂存、尚未提交的媒体片段。
   *
   * <p>插件只携带 {@link PluginPromptResources#stage} 返回的句柄；真正接收（物化为 durable RESOURCE、为 Session 建立引用并释放
   * upload owner）由宿主在接受事务内完成。
   */
  record Media(PluginPromptResources.StagedResource resource) implements PluginPromptContent {

    public Media {
      Objects.requireNonNull(resource, "resource");
    }
  }
}
