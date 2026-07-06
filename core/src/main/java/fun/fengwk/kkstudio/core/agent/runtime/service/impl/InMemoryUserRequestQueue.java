package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import java.util.ArrayList;
import java.util.List;

import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import java.util.ArrayList;
import java.util.List;

final class InMemoryUserRequestQueue implements UserRequestQueue {

  private final List<UserRequest> requests = new ArrayList<>();

  @Override
  public void submit(UserRequest userRequest) {
    requests.add(userRequest);
  }

  @Override
  public List<UserRequest> pollAll() {
    List<UserRequest> copied = List.copyOf(requests);
    requests.clear();
    return copied;
  }

  @Override
  public boolean isEmpty() {
    return requests.isEmpty();
  }
}
