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

package org.apache.skywalking.apm.plugin.httpasyncclient.v4;

import org.apache.http.HttpHost;
import org.apache.http.RequestLine;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.protocol.HttpContext;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.ContextSnapshot;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceConstructorInterceptor;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;

import java.net.MalformedURLException;
import java.net.URL;

import static org.apache.skywalking.apm.plugin.httpasyncclient.v4.SessionRequestCompleteInterceptor.CONTEXT_LOCAL;

/**
 * hold the snapshot in SkyWalkingDynamicField
 * @author lican
 */
public class SessionRequestConstructorInterceptor implements InstanceConstructorInterceptor {
    @Override
    public void onConstruct(EnhancedInstance objInst, Object[] allArguments) {
        if (!ContextManager.isActive()) {
            return;
        }

        HttpContext context = CONTEXT_LOCAL.get();
        if (context == null) {
            return;
        }

        final ContextCarrier contextCarrier = new ContextCarrier();
        HttpRequestWrapper requestWrapper = (HttpRequestWrapper) context.getAttribute(HttpClientContext.HTTP_REQUEST);
        HttpHost httpHost = (HttpHost) context.getAttribute(HttpClientContext.HTTP_TARGET_HOST);

        RequestLine requestLine = requestWrapper.getRequestLine();
        String uri = requestLine.getUri();
        String operationName = operationName(uri);
        int port = httpHost.getPort();
        AbstractSpan span = ContextManager.createExitSpan(operationName, contextCarrier, httpHost.getHostName() + ":" + (port == -1 ? 80 : port));
        span.setComponent(ComponentsDefine.HTTP_ASYNC_CLIENT);
        Tags.URL.set(span, requestWrapper.getOriginal().getRequestLine().getUri());
        Tags.HTTP.METHOD.set(span, requestLine.getMethod());
        SpanLayer.asHttp(span);
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            requestWrapper.setHeader(next.getHeadKey(), next.getHeadValue());
        }

        ContextSnapshot snapshot = ContextManager.capture();
        objInst.setSkyWalkingDynamicField(new Object[]{snapshot, operationName, requestWrapper.getOriginal().getRequestLine()});
        CONTEXT_LOCAL.remove();
    }

    private String operationName(String uri) {
        try {
            return uri.startsWith("http") ? new URL(uri).getPath() : uri;
        } catch (MalformedURLException e) {
            return uri;
        }
    }
}
