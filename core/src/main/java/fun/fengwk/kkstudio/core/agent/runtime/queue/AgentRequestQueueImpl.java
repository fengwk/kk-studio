package fun.fengwk.kkstudio.core.agent.runtime.queue;

import fun.fengwk.kkstudio.agent.runtime.AgentRequest;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author fengwk
 */
public class AgentRequestQueueImpl implements AgentRequestQueue {

    private final BlockingQueue<AgentRequest> queue = new LinkedBlockingQueue<>();

    @Override
    public void submit(AgentRequest request) {
        boolean offer = queue.offer(request);
        if (!offer) {
            throw new IllegalStateException("AgentRequestQueue is full");
        }
    }

    @Override
    public AgentRequest take() throws InterruptedException {
        return queue.take();
    }

}
