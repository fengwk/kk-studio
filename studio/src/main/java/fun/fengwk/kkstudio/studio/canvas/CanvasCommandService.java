package fun.fengwk.kkstudio.studio.canvas;

/**
 * Canvas commands 的写端口。
 *
 * <p>持久化、revision CAS 与幂等性由 adapter 负责。本接口是 web/core 使用的领域边界。
 *
 * <p>幂等键为 {@code commandId}（UUID/ULID 风格）；请求负载哈希由服务端根据规范的 {@code commandsJson}
 * 计算，客户端无法有意或无意地造成哈希冲突。
 */
public interface CanvasCommandService {

  CanvasDocument createCanvas(String title);

  CanvasSnapshot applyCommands(
      long canvasId, long baseRevision, String commandId, String commandsJson);
}
