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

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.impl.nio.reactor.SessionRequestImpl;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequestCallback;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;

class SessionRequestProxy extends SessionRequestImpl implements EnhancedInstance {

    private final AtomicReference<Object> atomicReference = new AtomicReference<Object>();
    private final SessionRequestCompleteInterceptor completeInterceptor = new SessionRequestCompleteInterceptor();
    private final SessionRequestFailInterceptor failInterceptor = new SessionRequestFailInterceptor();

    SessionRequestProxy(SocketAddress remoteAddress,
        SocketAddress localAddress,
        Object attachment,
        SessionRequestCallback callback) {

        super(remoteAddress, localAddress, attachment, callback);

        new SessionRequestConstructorInterceptor().onConstruct(this, new Object[0]);
    }

    @Override
    public Object getSkyWalkingDynamicField() {
        return atomicReference.get();
    }

    @Override
    public void setSkyWalkingDynamicField(Object value) {
        atomicReference.set(value);
    }

    @Override
    public void completed(IOSession session) {
        try {
            completeInterceptor.beforeMethod(this, null, null, null, null);
            super.completed(session);
            completeInterceptor.afterMethod(this, null, null, null, null);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void timeout() {
        try {
            failInterceptor.beforeMethod(this, null, null, null, null);
            super.timeout();
            failInterceptor.afterMethod(this, null, null, null, null);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void failed(IOException e) {
        try {
            failInterceptor.beforeMethod(this, null, null, null, null);
            super.failed(e);
            failInterceptor.afterMethod(this, null, null, null, null);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void cancel() {
        try {
            failInterceptor.beforeMethod(this, null, null, null, null);
            super.cancel();
            failInterceptor.afterMethod(this, null, null, null, null);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}
