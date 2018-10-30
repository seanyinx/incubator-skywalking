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

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.InterceptedCloseableHttpAsyncClient;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.SessionRequestImpl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.reactor.SessionRequestCallback;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.context.util.KeyValuePair;
import org.apache.skywalking.apm.agent.test.helper.SegmentHelper;
import org.apache.skywalking.apm.agent.test.helper.SpanHelper;
import org.apache.skywalking.apm.agent.test.tools.AgentServiceRule;
import org.apache.skywalking.apm.agent.test.tools.SegmentStorage;
import org.apache.skywalking.apm.agent.test.tools.SegmentStoragePoint;
import org.apache.skywalking.apm.agent.test.tools.TracingSegmentRunner;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import wiremock.org.apache.http.entity.ContentType;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.skywalking.apm.agent.core.context.SW3CarrierItem.HEADER_NAME;
import static org.apache.skywalking.apm.plugin.httpasyncclient.v4.SessionRequestCompleteInterceptor.CONTEXT_LOCAL;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.powermock.api.mockito.PowerMockito.whenNew;
import static wiremock.org.apache.http.HttpHeaders.CONTENT_TYPE;
import static wiremock.org.apache.http.HttpStatus.SC_OK;

/**
 * @author seanyinx
 */
@PowerMockIgnore("javax.net.ssl.*")
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TracingSegmentRunner.class)
@PrepareForTest({DefaultConnectingIOReactor.class, HttpAsyncClientBuilder.class})
public class HttpAsyncClientInterceptorIntegrationTest {

    @ClassRule
    public static final WireMockRule WIRE_MOCK_RULE = new WireMockRule(wireMockConfig().dynamicPort());
    private static final String PAYLOAD = "hello world";
    private final AtomicReference<HttpRequestWrapper> requestWrapperAtomicReference = new AtomicReference<HttpRequestWrapper>();

    @SegmentStoragePoint
    private SegmentStorage segmentStorage;

    @Rule
    public AgentServiceRule agentServiceRule = new AgentServiceRule();

    private HttpAsyncRequestExecutorInterceptor requestExecutorInterceptor;

    private CloseableHttpAsyncClient httpAsyncClient;
    private final List<String> messages = new ArrayList<String>(1);
    private final List<Throwable> exceptions = new ArrayList<Throwable>(1);

    @BeforeClass
    public static void beforeClass() {
        stubFor(get(urlEqualTo("/test-web/test"))
                .willReturn(
                        aResponse()
                                .withHeader(CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType())
                                .withStatus(SC_OK)
                                .withBody(PAYLOAD)));
    }

    @Before
    public void setUp() throws Exception {
        ServiceManager.INSTANCE.boot();
        requestExecutorInterceptor = new HttpAsyncRequestExecutorInterceptor();

        whenNew(HttpAsyncRequestExecutor.class).withAnyArguments().thenReturn(new HttpAsyncRequestExecutor() {
            @Override
            public void requestReady(NHttpClientConnection conn) {
                try {
                    final HttpContext context = CONTEXT_LOCAL.get();
                    if (context != null) {
                        requestWrapperAtomicReference.set((HttpRequestWrapper) context.getAttribute(HttpClientContext.HTTP_REQUEST));
                    }
                    requestExecutorInterceptor.beforeMethod(null, null, null, null, null);
                    super.requestReady(conn);
                    requestExecutorInterceptor.afterMethod(null, null, null, null, null);
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
        });

        whenNew("org.apache.http.impl.nio.client.InternalHttpAsyncClient")
                .withAnyArguments()
                .thenAnswer(new Answer<Object>() {
                    @Override
                    public Object answer(InvocationOnMock invocationOnMock) {
                        final Object[] arguments = invocationOnMock.getArguments();
                        return new InterceptedCloseableHttpAsyncClient(arguments);
                    }
                });

        httpAsyncClient = HttpAsyncClientBuilder.create().build();
        httpAsyncClient.start();

        mockSessionRequest();
    }

    @After
    public void tearDown() throws IOException {
        httpAsyncClient.close();
    }

    @Test
    public void shouldReportSpan() {
        ContextManager.createEntrySpan("mock-test", new ContextCarrier());

        HttpGet request = new HttpGet("http://localhost:" + WIRE_MOCK_RULE.port() + "/test-web/test");

        httpAsyncClient.execute(request, messageGatheringCallback(messages, exceptions));

        receivedPayloadEventually(messages);

        assertThat(messages, contains(PAYLOAD));
        assertThat(exceptions.isEmpty(), is(true));
        ContextManager.stopSpan();

        assertThat(segmentStorage.getTraceSegments().size(), is(2));

        TraceSegment traceSegment = findNeedSegment();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
        assertHttpSpan(spans.get(0));

        assertThat(requestWrapperAtomicReference.get().getFirstHeader(HEADER_NAME).getValue(),
                allOf(containsString(String.valueOf(traceSegment.getTraceSegmentId())),
                        containsString(String.valueOf(traceSegment.getRelatedGlobalTraces().get(0))))
        );
    }

    @Test
    public void shouldNotReportSpanIfNoParentSpan() {
        HttpGet request = new HttpGet("http://localhost:" + WIRE_MOCK_RULE.port() + "/test-web/test");

        httpAsyncClient.execute(request, messageGatheringCallback(messages, exceptions));

        receivedPayloadEventually(messages);

        assertThat(messages, contains(PAYLOAD));
        assertThat(exceptions.isEmpty(), is(true));

        assertThat(segmentStorage.getTraceSegments().size(), is(0));
        assertThat(requestWrapperAtomicReference.get(), is(nullValue()));
    }

    private void receivedPayloadEventually(final List<String> messages) {
        await().atMost(2, SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return !messages.isEmpty();
            }
        });
    }

    private FutureCallback<HttpResponse> messageGatheringCallback(
            final List<String> messages,
            final List<Throwable> exceptions) {

        return new FutureCallback<HttpResponse>() {
            @Override
            public void completed(HttpResponse result) {
                try {
                    messages.add(EntityUtils.toString(result.getEntity()));
                } catch (IOException e) {
                    exceptions.add(e);
                }
            }

            @Override
            public void failed(Exception ex) {
                exceptions.add(ex);
            }

            @Override
            public void cancelled() {
            }
        };
    }

    private TraceSegment findNeedSegment() {
        for (TraceSegment traceSegment : segmentStorage.getTraceSegments()) {
            if (SegmentHelper.getSpans(traceSegment).size() > 1) {
                return traceSegment;
            }
        }

        return null;
    }

    private void assertHttpSpan(AbstractTracingSpan span) {
        assertThat(span.getOperationName(), is("/test-web/test"));
        assertThat(SpanHelper.getComponentId(span), is(26));
        List<KeyValuePair> tags = SpanHelper.getTags(span);
        assertThat(tags.get(0).getValue(), is("http://localhost:" + WIRE_MOCK_RULE.port() + "/test-web/test"));
        assertThat(tags.get(1).getValue(), is("GET"));
        assertThat(span.isExit(), is(true));
    }

    private void mockSessionRequest() throws Exception {
        whenNew(SessionRequestImpl.class).withAnyArguments().then(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) {
                final Object[] arguments = invocationOnMock.getArguments();
                return new SessionRequestProxy((SocketAddress) arguments[0], (SocketAddress) arguments[1], arguments[2],
                        (SessionRequestCallback) arguments[3]);
            }
        });
    }
}
