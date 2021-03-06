package org.lancoder.master.dispatcher;

import java.util.logging.Logger;

import org.lancoder.common.Node;
import org.lancoder.common.events.Event;
import org.lancoder.common.events.EventEnum;
import org.lancoder.common.events.EventListener;
import org.lancoder.common.network.cluster.messages.TaskRequestMessage;
import org.lancoder.common.pool.Pool;
import org.lancoder.common.pool.PoolWorker;
import org.lancoder.common.task.ClientTask;

public class DispatcherPool extends Pool<DispatchItem> implements DispatcherListener {

	private static final int MAX_DISPATCHERS = 5;

	private EventListener listener;

	public DispatcherPool(EventListener listener) {
		super(MAX_DISPATCHERS);
		this.listener = listener;
	}

	@Override
	protected PoolWorker<DispatchItem> getPoolWorkerInstance() {
		return new Dispatcher(this);
	}

	@Override
	public synchronized void taskRefused(DispatchItem item) {
		Logger logger = Logger.getLogger("lancoder");

		ClientTask t = ((TaskRequestMessage) item.getMessage()).getTask();

		Node node = item.getNode();
		t.getProgress().reset();

		if (node.hasTask(t)) {
			node.getPendingTasks().remove(t);
		}
		node.unlock();

		logger.fine(String.format("Node %s refused task %d from job %s.%n",
				node.getName(), t.getTaskId(), t.getJobId()));

		listener.handle(new Event(EventEnum.DISPATCH_ITEM_REFUSED, item));
	}

	@Override
	public synchronized void taskAccepted(DispatchItem item) {
		Logger logger = Logger.getLogger("lancoder");

		ClientTask t = ((TaskRequestMessage) item.getMessage()).getTask();
		Node n = item.getNode();
		n.unlock();

		logger.fine(String.format("Node %s accepted task %d from %s.%n",
				n.getName(), t.getTaskId(), t.getJobId()));
		listener.handle(new Event(EventEnum.WORK_NEEDS_UPDATE));
	}

}
