package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

final class InMemoryUserRequestQueue implements UserRequestQueue {

  private final ConcurrentLinkedQueue<UserRequest> requests = new ConcurrentLinkedQueue<>();

  @Override
  public void submit(UserRequest userRequest) {
    requests.offer(userRequest);
  }

  @Override
  public List<UserRequest> pollAll() {
    List<UserRequest> drained = new ArrayList<>();
    UserRequest request;
    while ((request = requests.poll()) != null) {
      drained.add(request);
    }
    return List.copyOf(drained);
  }

  @Override
  public boolean isEmpty() {
    return requests.isEmpty();
  }
}
