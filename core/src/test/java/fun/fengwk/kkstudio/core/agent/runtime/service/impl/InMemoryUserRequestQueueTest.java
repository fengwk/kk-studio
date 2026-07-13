package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.UserRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * @author fengwk
 */
public class InMemoryUserRequestQueueTest {

  /** 多调用线程提交后收割，验证队列不会丢失或重复请求。 */
  @Test
  public void shouldDrainAllRequestsSubmittedConcurrently() throws Exception {
    int requestCount = 200;
    int workerCount = 4;
    InMemoryUserRequestQueue queue = new InMemoryUserRequestQueue();
    ExecutorService executorService = Executors.newFixedThreadPool(workerCount);
    CountDownLatch ready = new CountDownLatch(workerCount);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<? extends Future<?>> futures =
          IntStream.range(0, workerCount)
              .mapToObj(
                  workerIndex ->
                      executorService.submit(
                          () -> {
                            ready.countDown();
                            start.await();
                            for (int index = workerIndex;
                                index < requestCount;
                                index += workerCount) {
                              queue.submit(UserRequest.userRequest("message-" + index));
                            }
                            return null;
                          }))
              .toList();
      assertTrue(ready.await(5, TimeUnit.SECONDS));

      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }

      List<UserRequest> drained = queue.pollAll();
      Set<String> messages = new HashSet<>();
      for (UserRequest request : drained) {
        messages.add(request.getMessage());
      }
      assertEquals(requestCount, drained.size());
      assertEquals(requestCount, messages.size());
      assertTrue(queue.isEmpty());
    } finally {
      executorService.shutdownNow();
    }
  }
}
