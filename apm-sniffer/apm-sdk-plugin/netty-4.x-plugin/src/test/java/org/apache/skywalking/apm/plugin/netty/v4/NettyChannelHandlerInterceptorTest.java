/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.apm.plugin.netty.v4;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.test.helper.SegmentHelper;
import org.apache.skywalking.apm.agent.test.helper.SpanHelper;
import org.apache.skywalking.apm.agent.test.tools.AgentServiceRule;
import org.apache.skywalking.apm.agent.test.tools.SegmentStorage;
import org.apache.skywalking.apm.agent.test.tools.SegmentStoragePoint;
import org.apache.skywalking.apm.agent.test.tools.TracingSegmentRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.skywalking.apm.network.trace.component.ComponentsDefine.GRPC;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

/**
 * @author seanyinx
 */
@PowerMockIgnore("javax.net.ssl.*")
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TracingSegmentRunner.class)
public class NettyChannelHandlerInterceptorTest {
    private final MockNettyServer nettyServer = new MockNettyServer(8081, 15);
    private final List<String> messages = new ArrayList<String>();

    @SegmentStoragePoint
    private SegmentStorage segmentStorage;

    @Rule
    public AgentServiceRule agentServiceRule = new AgentServiceRule();

    private EventLoopGroup serverGroup;
    private final EventLoopGroup clientGroup = new NioEventLoopGroup();

    @Before
    public void setUp() {
        ServiceManager.INSTANCE.boot();
        serverGroup = nettyServer.run();
    }

    @After
    public void tearDown() {
        clientGroup.shutdownGracefully();
        serverGroup.shutdownGracefully();
    }

    @Test
    public void shouldReportSpan() {
        String payload = UUID.randomUUID().toString();
        channel().writeAndFlush(payload);

        receivedPayloadEventually(messages);

        assertThat(messages, contains(payload));

        assertThat(segmentStorage.getTraceSegments().size(), is(2));

        TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
        assertSpan(spans.get(0));
    }

    private void receivedPayloadEventually(final List<String> messages) {
        await().atMost(2, SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return !messages.isEmpty();
            }
        });
    }

    private void assertSpan(AbstractTracingSpan span) {
        assertThat(span.getOperationName(), is("netty/channelRead"));
        assertThat(SpanHelper.getComponentId(span), is(GRPC.getId()));
        assertThat(span.isEntry(), is(true));
    }

    private Channel channel() {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .remoteAddress(new InetSocketAddress("localhost", 8081))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        socketChannel.pipeline()
                                .addLast("lengthFieldBasedFrameDecoder",
                                        new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 0))
                                .addLast("lengthFieldPrepender",
                                        new LengthFieldPrepender(4, 0, false))
                                .addLast(new PacketCodec())
                                .addLast(new PacketCodec())
                                .addLast(new SimpleChannelInboundHandler<String>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext channelHandlerContext, String s) {
                                        messages.add(s);
                                    }
                                });
                    }
                });
        ChannelFuture future = bootstrap.connect().syncUninterruptibly();

        return future.channel();
    }
}
