package fun.fengwk.kkstudio.platform.plugin.prompt;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * 宿主提供的媒体准备端口：把插件侧的原始字节流暂存为可被 Prompt 接受事务消费的句柄。
 *
 * <p>暂存在接受事务外进行，只产出 {@link StagedResource} 这一对可持久化身份；上传/Blob 行、对象存储 key 与宿主消息结构都不暴露。
 */
public interface PluginPromptResources {

  /**
   * 读取一次输入并暂存为句柄；输入流由调用方拥有并关闭。
   *
   * <p>实现必须在事务外消费输入流、严格校验真实字节数与媒体类型；校验失败时自行回收本次产生的暂存，不返回句柄。
   *
   * @param filename 期望文件名；实现仍以暂存结果为准
   * @param mediaType 期望媒体类型；实现必须与真实探测结果比对
   * @param expectedSize 期望字节数；实现必须与真实大小比对
   */
  StagedResource stage(String filename, String mediaType, InputStream input, long expectedSize);

  /**
   * 丢弃尚未尝试提交的暂存句柄：用于准备阶段失败时的清理。
   *
   * <p>一旦 {@link PluginPromptTasks#submit} 可能已被接受，调用方不得再依赖异常猜测去清理已经属于任务的内容。
   */
  void discard(StagedResource resource);

  /** 尚未提交的暂存媒体句柄：只暴露可持久化身份。 */
  record StagedResource(UUID uploadId, UUID blobId) {

    public StagedResource {
      Objects.requireNonNull(uploadId, "uploadId");
      Objects.requireNonNull(blobId, "blobId");
    }
  }
}
