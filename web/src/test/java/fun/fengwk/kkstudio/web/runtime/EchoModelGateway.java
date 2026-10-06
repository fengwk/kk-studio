package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread join 集成测试使用的受控 {@link ModelGateway}：把请求中最后一条 USER 消息的文本原样回显为助手报告。
 *
 * <p>测试意图：join 的端到端行为必须由真实 Runtime 产生（真实 dispatcher、Model/Tool Processor、durable 历史、首次 idle
 * 匹配与父消息交付），但真实模型是非确定性外部付费依赖。回显规则让测试既能固定每个 turn 的产出，又能证明"结果沿执行树传递"—— 子执行收到父级交付的 receipt
 * 后，它的报告里必然再次出现下一级的报告与 Thread 身份。
 */
public final class EchoModelGateway implements ModelGateway {

  private static final String ECHO_PREFIX = "ACK:";

  private static final String NO_INPUT = "(no user input)";

  private final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();

  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    ProviderRequest request = execution.request();
    requests.add(request);
    return new Started(
        new Handle() {
          @Override
          public void cancel() {
            // 脚本化响应是同步内存交付：没有需要取消的外部执行。
          }

          @Override
          public void activate() {
            // 两阶段激活契约允许 Gateway 在 activate 期间同步回调；Runtime 会缓冲并在打开回调门控后按序重放。
            listener.onSucceeded(respond(request));
          }
        });
  }

  /** 已交付的 Provider 请求数（含 ToolResult continuation turn），供测试观测真实 Runtime 的调用次数。 */
  public int requestCount() {
    return requests.size();
  }

  private static ProviderResponse respond(ProviderRequest request) {
    return new ProviderResponse(
        ECHO_PREFIX + lastUserText(request),
        null,
        List.of(),
        GenerationStopReason.COMPLETE,
        usage(),
        null,
        null,
        null);
  }

  private static String lastUserText(ProviderRequest request) {
    List<ProviderMessage> messages = request.messages();
    for (int index = messages.size() - 1; index >= 0; index--) {
      ProviderMessage message = messages.get(index);
      if (message.role() != ProviderMessageRole.USER) {
        continue;
      }
      StringBuilder text = new StringBuilder();
      for (ProviderContentBlock block : message.contents()) {
        if (block instanceof ProviderTextBlock value) {
          text.append(value.text());
        }
      }
      if (!text.isEmpty()) {
        return text.toString();
      }
    }
    return NO_INPUT;
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }
}
