package io.github.pikaq.remoting;

import io.netty.util.concurrent.FastThreadLocal;

public class RemotingContextHolder {

	static final FastThreadLocal<RemotingContext> CONTEXT = new FastThreadLocal<>();

	public static RemotingContext current() {
		return CONTEXT.get();
	}

	public static void set(RemotingContext remotingContext) {
		CONTEXT.set(remotingContext);
	}

	public static void clear() {
		CONTEXT.remove();
	}
}
