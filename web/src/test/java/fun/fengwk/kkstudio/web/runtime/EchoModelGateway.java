package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;

import java.util.List;
import java.util.Map;
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
  public Admission admit(Execution execution) {
    Objects.requireNonNull(execution, "execution");
    ProviderRequest request = execution.request();
    return new Admitted() {
      @Override
      public Outcome run(Sink sink) {
        // 脚本化响应是同步内存交付：没有外部执行需要中断或释放。
        requests.add(request);
        return new Succeeded(new ProviderCompletion(respond(request), null));
      }

      @Override
      public void close() {}
    };
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
    return new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L);
  }
}
