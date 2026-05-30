package fun.fengwk.kkstudio.core.agent.runtime.event;

import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;

/**
 * @author fengwk
 */
public interface EventListener {

    void onEvent(Event event);

}
