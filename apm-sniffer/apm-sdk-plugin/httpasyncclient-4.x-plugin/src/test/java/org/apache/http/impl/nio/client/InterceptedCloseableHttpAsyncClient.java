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

package org.apache.http.impl.nio.client;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.Lookup;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;
import org.apache.skywalking.apm.plugin.httpasyncclient.v4.HttpAsyncClientInterceptor;

public class InterceptedCloseableHttpAsyncClient extends InternalHttpAsyncClient implements HttpAsyncClient {

    private final HttpAsyncClientInterceptor clientInterceptor = new HttpAsyncClientInterceptor();

    private InterceptedCloseableHttpAsyncClient(
            final NHttpClientConnectionManager connmgr,
            final ConnectionReuseStrategy connReuseStrategy,
            final ConnectionKeepAliveStrategy keepaliveStrategy,
            final ThreadFactory threadFactory,
            final NHttpClientEventHandler handler,
            final InternalClientExec exec,
            final Lookup<CookieSpecProvider> cookieSpecRegistry,
            final Lookup<AuthSchemeProvider> authSchemeRegistry,
            final CookieStore cookieStore,
            final CredentialsProvider credentialsProvider,
            final RequestConfig defaultConfig) {

        super(connmgr,
                connReuseStrategy,
                keepaliveStrategy,
                threadFactory,
                handler,
                exec,
                cookieSpecRegistry,
                authSchemeRegistry,
                cookieStore,
                credentialsProvider,
                defaultConfig);
    }

    public InterceptedCloseableHttpAsyncClient(Object[] arguments) {
        this(
                (NHttpClientConnectionManager) arguments[0],
                (ConnectionReuseStrategy) arguments[1],
                (ConnectionKeepAliveStrategy) arguments[2],
                (ThreadFactory) arguments[3],
                (NHttpClientEventHandler) arguments[4],
                (InternalClientExec) arguments[5],
                (Lookup) arguments[6],
                (Lookup) arguments[7],
                (CookieStore) arguments[8],
                (CredentialsProvider) arguments[9],
                (RequestConfig) arguments[10]
        );
    }

    @Override
    public <T> Future<T> execute(HttpAsyncRequestProducer requestProducer, HttpAsyncResponseConsumer<T> responseConsumer, HttpContext context,
                                 FutureCallback<T> callback) {

        final Object[] arguments = {requestProducer, responseConsumer, context, callback};
        try {
            clientInterceptor.beforeMethod(null, null, arguments, null, null);
            final Future<T> future = super.execute(requestProducer, (HttpAsyncResponseConsumer<T>) arguments[1], context,
                    (FutureCallback<T>) arguments[3]);
            clientInterceptor.afterMethod(null, null, arguments, null, null);
            return future;
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }
}
