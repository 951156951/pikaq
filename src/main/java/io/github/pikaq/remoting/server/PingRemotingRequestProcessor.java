package io.github.pikaq.remoting.server;

import io.github.pikaq.common.annotation.ServerSide;
import io.github.pikaq.remoting.protocol.RemotingRequestProcessor;
import io.github.pikaq.remoting.protocol.command.PingCommand;
import io.github.pikaq.remoting.protocol.command.PongCommand;
import io.netty.channel.ChannelHandlerContext;

@ServerSide
public class PingRemotingRequestProcessor implements RemotingRequestProcessor<PingCommand, PongCommand> {

	@Override
	public PongCommand handler(ChannelHandlerContext ctx, PingCommand request) {
		PongCommand command = new PongCommand();
		command.set("currentTimeMillis", System.currentTimeMillis());
		return command;
	}

}
