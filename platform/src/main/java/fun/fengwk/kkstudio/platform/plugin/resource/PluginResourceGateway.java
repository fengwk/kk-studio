package fun.fengwk.kkstudio.platform.plugin.resource;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.net.URI;
import java.util.UUID;

/**
 * Plugin 访问当前 Session Resource 与暂存远端媒体的受控端口。
 *
 * <p>这是 Plugin 与 Storage 之间唯一的窄接口，代替「把大媒体塞进 byte[]」或让 Plugin 自己颁发 URL：
 *
 * <ul>
 *   <li>{@link #resolveSessionResource(String)} 在签发输入前按当前调用线程解析 Session 并校验 {@code
 *       session_blob_ref}，只把该 Session 确实有权访问的 Resource 映射为受控短期 HTTPS 下载地址；
 *   <li>{@link #stageRemoteMedia} 把第三方或生成的媒体按有界流式写入全局 Blob 上传，返回 {@code blob-upload:<uploadId>}
 *       瞬态引用，交由统一的 ToolResult finalizer 与 Storage maintenance 完成校验、owner 转移或回收。
 * </ul>
 *
 * <p>实现必须固定禁用自动 redirect、要求每一跳都是 HTTPS、拒绝解析到私有网络的地址，并受 response/time/size budget 约束；媒体类型由声明族与实现自己的
 * MIME 嗅探共同校验，任何不满足约束的输入或输出都以 {@link PluginResourceUnavailableException} 终结，绝不以占位引用、本地路径或第三方临时 URL
 * 伪装成功。
 *
 * <p>当前部署没有绑定实现时，依赖媒体的 Tool 必须在发送请求前就确定性失败，不得消费额度后再失败。
 */
public interface PluginResourceGateway {

  /**
   * 把指定 Thread 所属 Session 有权访问的规范 Resource URI 解析为受控短期 HTTPS 下载地址。
   *
   * <p>授权上下文由调用方**显式**给出（Tool invocation context 里的 {@code threadId}），实现从 durable Thread 行解析
   * Session 并校验 {@code session_blob_ref}。因此本端口没有任何隐式调用者身份：拿不到 threadId 的调用必须在发送请求前失败，而不是继承
   * 某个环境变量或线程局部状态。
   *
   * @param threadId 本次调用的 Harness Thread id；实现据此解析 Session 并鉴权
   * @param resourceUri {@code kkstudio:/resources/<blobId>} 形式的会话 Resource URI
   * @throws PluginResourceUnavailableException threadId 无法解析出 Session、当前 Session 无权访问、Resource
   *     已删除或无法签发下载
   */
  URI resolveSessionResource(UUID threadId, String resourceUri);

  /**
   * 把远端媒体有界流式暂存为全局 Blob 上传，返回 {@code blob-upload:<uploadId>} 瞬态引用。
   *
   * @param remoteUri 远端媒体地址；实现必须自行做 HTTPS、重定向与私有网络校验
   * @param family 调用方声明的媒体族；实现必须用 MIME 嗅探结果复核，不得只信声明
   * @param name 建议的规范文件名（不含扩展名推导）
   * @throws PluginResourceUnavailableException 下载失败、超出预算或嗅探结果不属于声明族
   */
  ResourceRef stageRemoteMedia(URI remoteUri, PluginMediaFamily family, String name);
}
