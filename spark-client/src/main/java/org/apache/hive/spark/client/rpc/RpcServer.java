/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.spark.client.rpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.hadoop.hive.common.classification.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An RPC server. The server matches remote clients based on a secret that is generated on
 * the server - the secret needs to be given to the client through some other mechanism for
 * this to work.
 *
 * 服务匹配远程客户端
 */
@InterfaceAudience.Private
public class RpcServer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RpcServer.class);
    private static final SecureRandom RND = new SecureRandom();

    private final String address;
    private final Channel channel;
    private final EventLoopGroup group;
    private final int port;
    private final ConcurrentMap<String, ClientInfo> pendingClients;
    private final RpcConfiguration config;

    public RpcServer(Map<String, String> mapConf) throws IOException, InterruptedException {

        LOG.info("RpcServer 实现类配置 ===== {}", mapConf);
        this.config = new RpcConfiguration(mapConf);


        LOG.info("RpcServer NioEventLoopGroup group 设置成受线程");
        //线程数量和线程工程，设置成守护的线程
        this.group = new NioEventLoopGroup(this.config.getRpcThreadCount(), new ThreadFactoryBuilder().setNameFormat("RPC-Handler-%d").setDaemon(true).build());

        //基于netty设置线程处理器，NioServerSocketChannel非阻塞io，用于处理TCP套接字连接
        /**
         * NioServerSocketChannel 是 Netty 中的一种通道（Channel），用于实现基于 NIO（非阻塞 I/O） 的服务器端套接字。它是 Netty 的网络编程框架的一部分，通常用于处理 TCP 连接。         *
         * 主要功能和特点
         * 非阻塞 I/O:
         *
         * NioServerSocketChannel 使用 Java NIO 提供的非阻塞 I/O，这意味着它可以处理多个连接，而不需要为每个连接都创建一个线程。这使得它在高并发场景下表现良好。
         * 接受连接:
         *
         * 该通道负责监听客户端的连接请求。当有客户端尝试连接时，它会接受这些连接，并为每个连接创建一个新的 NioSocketChannel 实例来进行数据交换。
         * 事件驱动:
         *
         * Netty 是基于事件驱动的架构，NioServerSocketChannel 通过事件循环（EventLoop）来处理连接和I/O事件，使其在处理高并发时效率更高。
         * 配置灵活:
         *
         * 您可以通过 Netty 提供的丰富的配置选项设置 NioServerSocketChannel 的各种参数，比如连接超时、背压策略、SSL/TLS 支持等。
         */

        ServerBootstrap serverBootstrap = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                //加密配置
                SaslServerHandler saslHandler = new SaslServerHandler(config);
                //创建rpc实例,参数：需要认证处理的，配置、通道、组信息
                final Rpc newRpc = Rpc.createServer(saslHandler, config, ch, group);
                saslHandler.rpc = newRpc;

                Runnable cancelTask = new Runnable() {
                    @Override
                    public void run() {
                        LOG.warn("Timed out waiting for hello from client.");
                        //超时关闭rpc实例
                        newRpc.close();
                    }
                };
                //rpc超时退出任务
                saslHandler.cancelTask = group.schedule(cancelTask, RpcServer.this.config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);

            }
        }).option(ChannelOption.SO_REUSEADDR, true).childOption(ChannelOption.SO_KEEPALIVE, true);
        //ChannelOption.SO_REUSEADDR 是 Netty 中的一种通道选项，用于设置套接字的地址重用功能。将其设置为 true 允许多个套接字绑定到相同的地址和端口。

        this.channel = bindServerPort(serverBootstrap).channel();
        this.port = ((InetSocketAddress) channel.localAddress()).getPort();
        this.pendingClients = Maps.newConcurrentMap();
        this.address = this.config.getServerAddress();
    }

    /**
     * Retry the list of configured ports until one is found
     *
     * @param serverBootstrap
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    private ChannelFuture bindServerPort(ServerBootstrap serverBootstrap) throws InterruptedException, IOException {
        List<Integer> ports = config.getServerPorts();
        if (ports.contains(0)) {
            return serverBootstrap.bind(0).sync();
        } else {

            Random rand = new Random();
            while (!ports.isEmpty()) {
                int index = rand.nextInt(ports.size());
                int port = ports.get(index);
                LOG.info("RpcServer 绑定随机的端口======={}", port);
                ports.remove(index);
                try {
                    return serverBootstrap.bind(port).sync();
                } catch (Exception e) {
                    // Retry the next port
                }
            }
            throw new IOException("No available ports from configured RPC Server ports for HiveServer2");
        }
    }

    /**
     * Tells the RPC server to expect a connection from a new client.
     *
     * @param clientId         An identifier for the client. Must be unique.
     * @param secret           The secret the client will send to the server to identify itself.
     * @param serverDispatcher The dispatcher to use when setting up the RPC instance.
     * @return A future that can be used to wait for the client connection, which also provides the
     * secret needed for the client to connect.
     */
    public Future<Rpc> registerClient(final String clientId, String secret, RpcDispatcher serverDispatcher) {
        return registerClient(clientId, secret, serverDispatcher, config.getServerConnectTimeoutMs());
    }

    /**
     * 这段代码的主要功能是异步注册一个客户端，设置超时机制，并管理正在进行的客户端连接。通过使用 Promise 和 Future，它允许调用者在稍后处理连接结果，同时避免阻塞主线程。
     * @param clientId
     * @param secret
     * @param serverDispatcher
     * @param clientTimeoutMs
     * @return
     */
    @VisibleForTesting
    Future<Rpc> registerClient(final String clientId, String secret, RpcDispatcher serverDispatcher, long clientTimeoutMs) {
        final Promise<Rpc> promise = group.next().newPromise();
        //创建一个新的 Promise<Rpc> 对象，用于表示异步操作的结果。

        Runnable timeout = () -> promise.setFailure(new TimeoutException("Timed out waiting for client connection."));

        //客户端超时配置
        ScheduledFuture<?> timeoutFuture = group.schedule(timeout, clientTimeoutMs, TimeUnit.MILLISECONDS);
        final ClientInfo client = new ClientInfo(clientId, promise, secret, serverDispatcher, timeoutFuture);
        if (pendingClients.putIfAbsent(clientId, client) != null) {
            throw new IllegalStateException(String.format("Client '%s' already registered.", clientId));
        }

        promise.addListener((GenericFutureListener<Promise<Rpc>>) p -> {
            if (!p.isSuccess()) {
                pendingClients.remove(clientId);
            }
        });

        return promise;
    }

    /**
     * Tells the RPC server to cancel the connection from an existing pending client
     *
     * @param clientId The identifier for the client
     * @param msg      The error message about why the connection should be canceled
     */
    public void cancelClient(final String clientId, final String msg) {
        final ClientInfo cinfo = pendingClients.remove(clientId);
        if (cinfo == null) {
            // Nothing to be done here.
            return;
        }
        cinfo.timeoutFuture.cancel(true);
        if (!cinfo.promise.isDone()) {
            cinfo.promise.setFailure(new RuntimeException(String.format("Cancel client '%s'. Error: " + msg, clientId)));
        }
    }

    /**
     * Creates a secret for identifying a client connection.
     */
    public String createSecret() {
        byte[] secret = new byte[config.getSecretBits() / 8];
        RND.nextBytes(secret);

        StringBuilder sb = new StringBuilder();
        for (byte b : secret) {
            if (b < 10) {
                sb.append("0");
            }
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void close() {
        try {
            channel.close();
            for (ClientInfo client : pendingClients.values()) {
                client.promise.cancel(true);
            }
            pendingClients.clear();
        } finally {
            group.shutdownGracefully();
        }
    }

    private class SaslServerHandler extends SaslHandler implements CallbackHandler {

        private final SaslServer server;
        private Rpc rpc;
        private ScheduledFuture<?> cancelTask;
        private String clientId;
        private ClientInfo client;

        SaslServerHandler(RpcConfiguration config) throws IOException {
            super(config);
            this.server = Sasl.createSaslServer(config.getSaslMechanism(), Rpc.SASL_PROTOCOL, Rpc.SASL_REALM, config.getSaslOptions(), this);
        }

        @Override
        protected boolean isComplete() {
            return server.isComplete();
        }

        @Override
        protected String getNegotiatedProperty(String name) {
            return (String) server.getNegotiatedProperty(name);
        }

        @Override
        protected Rpc.SaslMessage update(Rpc.SaslMessage challenge) throws IOException {
            if (clientId == null) {
                Preconditions.checkArgument(challenge.clientId != null, "Missing client ID in SASL handshake.");
                clientId = challenge.clientId;
                client = pendingClients.get(clientId);
                Preconditions.checkArgument(client != null, "Unexpected client ID '%s' in SASL handshake.", clientId);
            }
            //challenge.payload是有效的荷载
            return new Rpc.SaslMessage(server.evaluateResponse(challenge.payload));
        }

        @Override
        public byte[] wrap(byte[] data, int offset, int len) throws IOException {
            return server.wrap(data, offset, len);
        }

        @Override
        public byte[] unwrap(byte[] data, int offset, int len) throws IOException {
            return server.unwrap(data, offset, len);
        }

        @Override
        public void dispose() throws IOException {
            if (!server.isComplete()) {
                onError(new SaslException("Server closed before SASL negotiation finished."));
            }
            server.dispose();
        }

        @Override
        protected void onComplete() throws Exception {
            cancelTask.cancel(true);
            client.timeoutFuture.cancel(true);
            rpc.setDispatcher(client.dispatcher);
            client.promise.setSuccess(rpc);
            pendingClients.remove(client.id);
        }

        @Override
        protected void onError(Throwable error) {
            cancelTask.cancel(true);
            if (client != null) {
                client.timeoutFuture.cancel(true);
                if (!client.promise.isDone()) {
                    client.promise.setFailure(error);
                }
            }
        }

        @Override
        public void handle(Callback[] callbacks) {
            Preconditions.checkState(client != null, "Handshake not initialized yet.");
            for (Callback cb : callbacks) {
                if (cb instanceof NameCallback) {
                    ((NameCallback) cb).setName(clientId);
                } else if (cb instanceof PasswordCallback) {
                    ((PasswordCallback) cb).setPassword(client.secret.toCharArray());
                } else if (cb instanceof AuthorizeCallback) {
                    ((AuthorizeCallback) cb).setAuthorized(true);
                } else if (cb instanceof RealmCallback) {
                    RealmCallback rb = (RealmCallback) cb;
                    rb.setText(rb.getDefaultText());
                }
            }
        }

    }

    private static class ClientInfo {

        final String id;
        final Promise<Rpc> promise;
        final String secret;
        final RpcDispatcher dispatcher;
        final ScheduledFuture<?> timeoutFuture;

        private ClientInfo(String id, Promise<Rpc> promise, String secret, RpcDispatcher dispatcher, ScheduledFuture<?> timeoutFuture) {
            this.id = id;
            this.promise = promise;
            this.secret = secret;
            this.dispatcher = dispatcher;
            this.timeoutFuture = timeoutFuture;
        }

    }

}
