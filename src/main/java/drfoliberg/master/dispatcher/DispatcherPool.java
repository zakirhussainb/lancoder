package drfoliberg.master.dispatcher;

import java.util.ArrayList;

import org.eclipse.jetty.util.BlockingArrayQueue;

import drfoliberg.common.Service;

public class DispatcherPool extends Service implements DispatcherListener {

	private static final int MAX_DISPATCHERS = 5;

	private BlockingArrayQueue<DispatchItem> toDispatch = new BlockingArrayQueue<>();
	private ArrayList<HttpDispatcher> dispatchers = new ArrayList<>();
	private DispatcherListener listener;
	private ThreadGroup threads = new ThreadGroup("dispatcherThreads");

	public DispatcherPool(DispatcherListener listener) {
		this.listener = listener;
	}

	private HttpDispatcher createNewDispatcher() {
		System.err.println("Creating new dispatcher");
		HttpDispatcher dispatcher = new HttpDispatcher(listener);
		dispatchers.add(dispatcher);
		Thread t = new Thread(threads, dispatcher);
		t.start();
		return dispatcher;
	}

	private HttpDispatcher getFreeDispatcher() {
		for (HttpDispatcher dispatcher : dispatchers) {
			if (dispatcher.isFree()) {
				return dispatcher;
			}
		}
		if (dispatchers.size() < MAX_DISPATCHERS) {
			return createNewDispatcher();
		}
		System.err.println("Maximum dispatcher reached.");
		return null;
	}

	public synchronized void dispatch(DispatchItem item) {
		HttpDispatcher dispatcher = getFreeDispatcher();
		if (dispatcher != null) {
			dispatcher.queue(item);
		} else {
			toDispatch.add(item);
		}
	}

	private synchronized void update() {
		DispatchItem item = null;
		HttpDispatcher dispatcher = null;
		if ((item = toDispatch.poll()) != null && (dispatcher = getFreeDispatcher()) != null) {
			dispatcher.queue(item);
		}
	}

	@Override
	public void taskRefused(DispatchItem item) {
		this.toDispatch.remove(item);
		this.listener.taskRefused(item);
		update();
	}

	@Override
	public void taskAccepted(DispatchItem item) {
		this.toDispatch.remove(item);
		this.listener.taskAccepted(item);
		update();
	}

	@Override
	public void stop() {
		super.stop();
		this.threads.interrupt();
	}

}