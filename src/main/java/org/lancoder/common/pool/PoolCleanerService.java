package org.lancoder.common.pool;

import java.util.ArrayList;

import org.lancoder.common.RunnableService;

public class PoolCleanerService extends RunnableService {

	private final static long CHECK_DELAY_MSEC = 1000 * 30;

	private final ArrayList<Cleanable> cleanables = new ArrayList<>();

	@Override
	public void run() {
		while (!close) {
			try {
				for (Cleanable cleanable : cleanables) {
					if (cleanable.clean()) {
						System.out.printf("Cleaned %s%n", cleanable.getClass().getSimpleName());
					}
				}
				Thread.sleep(CHECK_DELAY_MSEC);
			} catch (InterruptedException e) {
				System.err.println("pool cleaner interrupted");
			}
		}
		System.err.println("pool cleaner closed");
	}

	public void addCleanable(Cleanable c) {
		this.cleanables.add(c);
	}

	@Override
	public void serviceFailure(Exception e) {
		e.printStackTrace();
	}

}
