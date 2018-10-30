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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;

public class MockNettyServer {

    private final IdleHandler idleHandler;
    private final EchoHandler echoHandler;
    private final int port;
    private final int idleTimeSeconds;

    public MockNettyServer(int port, int idleTimeSeconds) {
        this.idleHandler = new IdleHandler();
        this.port = port;
        this.idleTimeSeconds = idleTimeSeconds;
        this.echoHandler = new EchoHandler();
    }

    public EventLoopGroup run() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();

        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(new InetSocketAddress(port))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast("idleStateHandler", new IdleStateHandler(idleTimeSeconds, 0, 0))
                                .addLast("idleHandler", idleHandler)
                                .addLast("lengthFieldBasedFrameDecoder",
                                        new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 0))
                                .addLast("lengthFieldPrepender",
                                        new LengthFieldPrepender(4, 0, false))
                                .addLast("packetDecoder", new PacketCodec())
                                .addLast("packetEncoder", new PacketCodec())
                                .addLast("psServerHandler", echoHandler);
                    }
                });


        bootstrap.bind().syncUninterruptibly();
        return bossGroup;
    }
}
