package io.github.pikaq.remoting;


import java.util.concurrent.TimeUnit;

import org.junit.Assert;

import io.github.pikaq.client.ClientConfig;
import io.github.pikaq.client.SimpleClient;
import io.github.pikaq.common.exception.RemoteClientException;
import io.github.pikaq.common.exception.RemotingSendRequestException;
import io.github.pikaq.protocol.command.RemotingCommand;
import io.github.pikaq.protocol.command.RequestCode;
import io.github.pikaq.protocol.command.body.CarrierCommandBody;

public class ClientSendMessageTest {

	// junit会退出去 大坑
	public static void main(String[] args) throws RemoteClientException, RemotingSendRequestException, InterruptedException {
		ClientConfig clientConfig = ClientConfig.create()
				.connectTimeoutMillis(5000)
				.startFailReconnectTimes(3)
				.build();

		Assert.assertEquals(clientConfig.getConnectTimeoutMillis(), 5000);
		Assert.assertEquals(clientConfig.getStartFailReconnectTimes(), 3);

		SimpleClient client = new SimpleClient(clientConfig);
		
		client.connectWithRetry("127.0.0.1:8443");
		
		
		RemotingCommand request = new RemotingCommand();
		request.setBody(CarrierCommandBody.buildString(true, "hehe", "OK"));
		request.setResponsible(true);
		request.setRequestCode(RequestCode.CARRIER.getCode());
		
		while (true) {
			System.out.println("send.");
			client.invokeOneway("127.0.0.1:8443", request);
			TimeUnit.SECONDS.sleep(3);
		}
		
	}
}
