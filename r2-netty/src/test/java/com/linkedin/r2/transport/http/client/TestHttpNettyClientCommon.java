/*
   Copyright (c) 2018 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.r2.transport.http.client;

import com.linkedin.common.callback.FutureCallback;
import com.linkedin.pegasus.io.netty.channel.EventLoopGroup;
import com.linkedin.pegasus.io.netty.channel.nio.NioEventLoopGroup;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.Messages;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.transport.common.bridge.client.TransportCallbackAdapter;
import com.linkedin.r2.transport.common.bridge.common.TransportCallback;
import com.linkedin.r2.transport.http.client.common.AbstractNettyClient;
import com.linkedin.test.util.DataGeneration;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.linkedin.test.util.ExceptionTestUtil.verifyCauseChain;


/**
 * @author Francesco Capponi (fcapponi@linkedin.com)
 */
public class TestHttpNettyClientCommon
{
  private EventLoopGroup _eventLoop;
  private ScheduledExecutorService _scheduler;

  @BeforeClass
  public void setup()
  {
    _eventLoop = new NioEventLoopGroup();
    _scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterClass
  public void tearDown()
  {
    _scheduler.shutdown();
    _eventLoop.shutdownGracefully();
  }

  @DataProvider
  public static Object[][] isStreamAndHigher()
  {
    return DataGeneration.generateAllBooleanCombinationMatrix(2);
  }

  /**
   * Testing making request with custom-perRequest timeout, higher and lower than request timeout,
   * d2 or http requests and check it is working
   */
  @SuppressWarnings("unchecked")
  @Test(dataProvider = "isStreamAndHigher")
  public void testPerRequestTimeout(boolean isStream, boolean isHigherThanDefault)
      throws InterruptedException, IOException
  {
    TestServer testServer = new TestServer();

    int defaultRequestTimeout = 300;
    int requestTimeoutPerRequest = isHigherThanDefault ? defaultRequestTimeout + 200 : defaultRequestTimeout - 200;

    HttpClientBuilder clientBuilder =
        new HttpClientBuilder(_eventLoop, _scheduler).setRequestTimeout(defaultRequestTimeout);
    AbstractNettyClient<?, ?> client = isStream ? clientBuilder.buildStreamClient() : clientBuilder.buildRestClient();

    RestRequest r = new RestRequestBuilder(testServer.getNoResponseURI()).build();

    RequestContext requestContext = new RequestContext();
    requestContext.putLocalAttr(R2Constants.REQUEST_TIMEOUT, requestTimeoutPerRequest);

    long startTime = System.currentTimeMillis();
    FutureCallback<?> cb = new FutureCallback<>();

    if (isStream)
    {
      TransportCallback<StreamResponse> callback = new TransportCallbackAdapter<>((FutureCallback<StreamResponse>) cb);
      client.streamRequest(Messages.toStreamRequest(r), requestContext, new HashMap<>(), callback);
    } else
    {
      TransportCallback<RestResponse> callback = new TransportCallbackAdapter<>((FutureCallback<RestResponse>) cb);
      client.restRequest(r, requestContext, new HashMap<>(), callback);
    }
    try
    {
      // This timeout needs to be significantly larger than the getTimeout of the netty client;
      // we're testing that the client will generate its own timeout
      cb.get(10, TimeUnit.SECONDS);
      Assert.fail("Get was supposed to time out");
    } catch (TimeoutException e)
    {
      // TimeoutException means the timeout for Future.get() elapsed and nothing happened.
      // Instead, we are expecting our callback to be invoked before the future timeout
      // with a timeout generated by the HttpNettyClient.
      Assert.fail("Unexpected TimeoutException, should have been ExecutionException", e);
    } catch (ExecutionException e)
    {
      verifyCauseChain(e, RemoteInvocationException.class, TimeoutException.class);
      long endTime = System.currentTimeMillis();

      Assert.assertEquals((endTime - startTime) > defaultRequestTimeout, isHigherThanDefault,
          "The request timed out after " + (endTime - startTime) + "ms but it was supposed to be about " + (
              isHigherThanDefault ? "higher" : "lower") + " than " + defaultRequestTimeout + "ms");

      Assert.assertTrue((endTime - startTime) - requestTimeoutPerRequest < 150, // 150 ms of accuracy
          "The request timed out after " + (endTime - startTime) + "ms but it was supposed to be about "
              + requestTimeoutPerRequest + "ms");
    }
    testServer.shutdown();
  }
}
