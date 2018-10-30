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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.ContextSnapshot;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Sharable
class EchoHandler extends SimpleChannelInboundHandler<String> {

    private final SimpleChannelInboundHandlerInterceptor interceptor = new SimpleChannelInboundHandlerInterceptor();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ContextSnapshot snapshot;

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final String payload) {
        Object[] arguments = {ctx, payload};
        interceptor.beforeMethod(null, null, arguments, null, null);

        // capture snapshot for cross thread
        if (ContextManager.isActive()) {
            snapshot = ContextManager.capture();
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                ContextManager.createLocalSpan("async/local");
                if (snapshot != null) {
                    ContextManager.continued(snapshot);
                }

                final ContextCarrier contextCarrier = new ContextCarrier();
                AbstractSpan span = ContextManager.createExitSpan("foo", contextCarrier, "http://remote:8081");
                span.setComponent(ComponentsDefine.HTTP_ASYNC_CLIENT);
                Tags.URL.set(span, "remote:8081");
                Tags.HTTP.METHOD.set(span, "GET");
                SpanLayer.asHttp(span);

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // close exit span
                if (ContextManager.isActive()) {
                    ContextManager.stopSpan();
                }

                // close local span
                if (ContextManager.isActive()) {
                    ContextManager.stopSpan();
                }

                ctx.writeAndFlush(payload);
            }
        });

        interceptor.afterMethod(null, null, arguments, null, null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.writeAndFlush("oops");
    }
}
