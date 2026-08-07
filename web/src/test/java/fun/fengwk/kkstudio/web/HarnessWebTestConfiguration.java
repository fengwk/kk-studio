package fun.fengwk.kkstudio.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.Executor;

/**
 * 共享 web 上下文的测试传输 executor 覆写。
 *
 * <p>强制 Harness SSE 轮询运行在调用线程上，使 MockMvc 异步分发能确定性地观察到事件。Harness Runtime 组合根（{@code
 * web.runtime}）提供真实 bean；{@code workers-enabled=false} 让控制/查询平面可用而不启动 worker dispatcher/listener。
 */
@Configuration
public class HarnessWebTestConfiguration {

  @Bean(name = "harnessEventStreamTaskExecutor")
  @Primary
  public Executor harnessEventStreamTaskExecutor() {
    return Runnable::run;
  }
}
