package io.github.pikaq;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.Mapper;
import akka.dispatch.OnFailure;
import akka.dispatch.OnSuccess;
import akka.pattern.Patterns;
import akka.util.Timeout;
import io.github.pikaq.common.exception.RemotingSendRequestException;
import io.github.pikaq.common.exception.RemotingTimeoutException;
import io.github.pikaq.common.util.NameThreadFactoryImpl;
import io.github.pikaq.common.util.SingletonFactoy;
import io.github.pikaq.initialization.support.Initializer;
import io.github.pikaq.protocol.command.RemotingCommand;
import io.github.pikaq.server.DispatcherActor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import scala.concurrent.Future;

/**
 * netty远程抽象实现
 * 
 * <p>
 * 考虑到远程消息的发送其实没有明显的C-S界限，Server端既可以作为消息接收端同样也可作为主动发送端，因而这一层不在区分Client/Server端。
 * 
 * 具体的实现由下层的消息类型决定是请求消息还是响应消息。
 * 
 * </p>
 * 
 * @author pleuvoir
 *
 */
@Slf4j
@SuppressWarnings("all")
public class RemotingAbstract {

	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	public RemotingAbstract() {
		clearNoResponseExecutor.scheduleAtFixedRate(() -> clearNoResponse(), 1, 30, TimeUnit.SECONDS);
		Initializer.init();
	}

	/**
	 * 保存正在处理的消息请求<br>
	 * 因为netty是以异步发送，所以发送后接受到的响应通过此寻找之前的对应关系
	 */
	private final ConcurrentHashMap<String /* messageId */, RemotingFuture> pendings = new ConcurrentHashMap<>(256);

	/**
	 * 接收到响应消息异步回调线程池
	 */
	private ExecutorService callbackExecutor = Executors.newFixedThreadPool(
			Runtime.getRuntime().availableProcessors() + 1, new NameThreadFactoryImpl("callbackExecutor", true));

	/**
	 * 清空长时间未响应请求定时线程
	 */
	private ScheduledExecutorService clearNoResponseExecutor = Executors
			.newSingleThreadScheduledExecutor((new NameThreadFactoryImpl("clearNoResponseExecutor")));


	/**
	 * 处理接收到的消息
	 */
	protected void processMessageReceived(ChannelHandlerContext ctx, RemotingCommand cmd) throws Exception {
		if (cmd != null) {
			switch (cmd.getCommandType()) {
			case REQUEST_COMMAND:
				processRequestCommand(ctx, cmd); // 处理接收到的请求消息，如果是需要回应的，则回应
				break;
			case RESPONSE_COMMAND:
				processResponseCommand(ctx, cmd); // 处理接收到的响应消息，一般是发送完请求消息后对端响应的内容，执行回调即完成，不会再应答
				break;
			default:
				log.error("unknow commandtype . {}", cmd.toJSON());
				break;
			}
		}
	}
	
	private void processRequestCommand(ChannelHandlerContext ctx, final RemotingCommand request) {
		// DispatcherActor不能通过new的方式创建
		RemoteInvokerContext invokerContext = RemoteInvokerContext.create().ctx(ctx).request(request).build();

		ActorSystem system = SingletonFactoy.get(ActorSystem.class);
		ActorRef actorRef = system.actorOf(Props.create(DispatcherActor.class));
		// 业务超时时间
		Timeout timeout = Timeout.apply(PikaqConst.OPT_TIMEOUT, TimeUnit.SECONDS);

		Future<RemotingCommand> future = Patterns.ask(actorRef, invokerContext, timeout)
				.map(new Mapper<Object, RemotingCommand>() {
					@Override
					public RemotingCommand apply(Object parameter) {
						return (RemotingCommand) parameter;
					}
				}, system.dispatcher());

		// 成功回调
		future.onSuccess(new OnSuccess<RemotingCommand>() {
			@Override
			public void onSuccess(RemotingCommand response) throws Throwable {
				if (request.isResponsible()) {
					response.setMessageId(request.getMessageId());
					ctx.writeAndFlush(response);
					logger.debug("命令处理结束：request={}，response={}", request.toJSON(), response.toJSON());
				} else {
					logger.debug("命令处理结束，消息无需响应。request={}，response={}", request.toJSON(), response.toJSON());
				}
				system.stop(actorRef);
			}
		}, system.dispatcher());

		// 失败回调
		future.onFailure(new OnFailure() {
			@Override
			public void onFailure(Throwable e) throws Throwable {
				logger.error(e.getMessage(), e);
				system.stop(actorRef);
			}
		}, system.dispatcher());
	}
	
	/**
	 * 处理之前发送给远端请求后 现在远端响应过来的消息
	 */
	protected void processResponseCommand(ChannelHandlerContext ctx, final RemotingCommand response) {
		final String messageId = response.getMessageId();
		try {
			// 获取messageId，之前发送请求时设置的值和这次响应的是一致的
			RemotingFuture prev = pendings.get(messageId);
			// 出现为空的情况说明定时任务清空过期请求容错时间太短了
			if (prev == null) {
				log.error("processResponseCommand response, but not found RemotingFuture,messageId={}",
						response.getMessageId());
				return;
			}
			// 异步执行回调有需要的话
			if (prev.getInvokeCallback() != null) {
				this.callbackExecutor.submit(() -> prev.onReceiveResponse());
			}
			// 如果发送端在等待那么通知其继续执行
			prev.completeResponse(response);
		} finally {
			// 保证所有的清空是在处理响应时清空，这样就有个问题如果对端因网络问题未到达，需要使用定时任务清空pendings中太久没有应答的messageId
			pendings.remove(messageId);
		}
	}

	/**
	 * 发送同步请求
	 */
	protected RemotingCommand invokeSyncImpl(final Channel channel, final RemotingCommand request,
			final long timeoutMillis) throws RemotingTimeoutException, RemotingSendRequestException {
		String messageId = request.getMessageId();
		final RemotingFuture remotingFuture = new RemotingFuture(messageId, timeoutMillis, null);
		// 保存请求响应对应关系
		pendings.put(messageId, remotingFuture);
		channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				if (future.isSuccess()) {
					remotingFuture.setSendRequestOK(true);
				} else {
					remotingFuture.setSendRequestOK(false);
					remotingFuture.setCause(future.cause());
					remotingFuture.completeResponse(null); // 通知发送端等待结束
					log.warn("send a request command to channel <" + channel.remoteAddress() + "> failed.");
				}
			}
		});
		// 同步等待响应
		RemotingCommand response = remotingFuture.waitResponse(timeoutMillis);
		if (response == null) {
			if (remotingFuture.isSendRequestOK()) {
				// 如果发送成功了还返回null，只能是等待对端响应超时了
				throw new RemotingTimeoutException("response return null");
			} else {
				// 发送失败
				throw new RemotingSendRequestException();
			}
		}
		return response;
	}

	/**
	 * 发送异步消息
	 */
	protected void invokeAsyncImpl(final Channel channel, final RemotingCommand request,
			final InvokeCallback invokeCallback) throws RemotingSendRequestException {
		final String messageId = request.getMessageId();
		final RemotingFuture remotingFuture = new RemotingFuture(messageId, null, invokeCallback);
		pendings.put(messageId, remotingFuture);
		channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture f) throws Exception {
				if (f.isSuccess()) {
					// 如果该消息是可回复的会由响应处理清除pendings，不可回复会由定时任务清除过期请求
					remotingFuture.setSendRequestOK(true);
					return;
				}
				// 异常直接移除即可
				pendings.remove(messageId);
				// 异常回调
				if (invokeCallback != null) {
					remotingFuture.setSendRequestOK(false);
					remotingFuture.setCause(f.cause());
					remotingFuture.setResponseCommand(null);
					RemotingAbstract.this.callbackExecutor.submit(() -> remotingFuture.onRequestException());
					log.warn("send a request command to channel <" + channel.remoteAddress() + "> failed.");
				}

			}
		});
	}

	/**
	 * 发送Oneway请求
	 */
	protected void invokeOnewayImpl(final Channel channel, final RemotingCommand request)
			throws RemotingSendRequestException {
		// 无需响应
		request.setResponsible(false);
		channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture f) throws Exception {
				if (!f.isSuccess()) {
					log.warn("send a request command to channel <" + channel.remoteAddress() + "> failed. ", f.cause());
				}
			}
		});
	}

	/**
	 * 清空过期请求
	 */
	private void clearNoResponse() {
		final List<RemotingFuture> rfList = new LinkedList<RemotingFuture>();
		Iterator<Entry<String, RemotingFuture>> it = this.pendings.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, RemotingFuture> next = it.next();
			RemotingFuture rep = next.getValue();
			// 发现超过当前时间30秒的直接移除，一般的网络通信也不会有30秒这么久，出现这么久应该是对方不会再响应了
			// 12:00发送的消息 超时时间1分钟 12:01到达超时时间，当前 12:01:35 即可认为它不会再响应了
			if ((rep.getBeginTimestamp() + rep.getTimeoutMillis() < System.currentTimeMillis() - 30000L)) {
				it.remove();
				rfList.add(rep);
				log.warn("remove timeout request, " + rep);
			}
		}
	}
	
	protected void shutdown(){
		SingletonFactoy.get(ActorSystem.class).terminate();
		MoreExecutors.shutdownAndAwaitTermination(clearNoResponseExecutor, 8, TimeUnit.SECONDS);
	};
}
