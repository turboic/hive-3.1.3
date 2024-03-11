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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;

import org.apache.hive.spark.client.BaseProtocol;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInputStream;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.base.Preconditions;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

/**
 * Codec that serializes / deserializes objects using Kryo. Objects are encoded with a 4-byte
 * header with the length of the serialized data.
 */
public class KryoMessageCodec extends ByteToMessageCodec<Object> {

  private static final Logger LOG = LoggerFactory.getLogger(KryoMessageCodec.class);

  // Kryo docs say 0-8 are taken. Strange things happen if you don't set an ID when registering
  // classes.
  private static final int REG_ID_BASE = 16;

  private final int maxMessageSize;
  private final List<Class<?>> messages;
  private final ThreadLocal<Kryo> kryos = new ThreadLocal<Kryo>() {
    @Override
    protected Kryo initialValue() {
      Kryo kryo = new Kryo();
      java.security.ProtectionDomain pd = Kryo.class.getProtectionDomain();
      CodeSource cs = pd.getCodeSource();
      LOG.info("KryoMessageCodec resKryo from ===========宋小宝 ======= {}",cs.getLocation());
      int count = 0;
      for (Class<?> klass : messages) {
        LOG.info("KryoMessageCodec register from =========== {} ======= {}",count,klass.getName());
        kryo.register(klass, REG_ID_BASE + count);
        count++;
      }
      LOG.info("KryoMessageCodec resKryo from ===========小沈阳 ======= {}",cs.getLocation());
      java.security.ProtectionDomain StdPd = StdInstantiatorStrategy.class.getProtectionDomain();
      CodeSource codeSource = StdPd.getCodeSource();
      LOG.info("Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy() from ===========赵丽颖 ======= {}",codeSource.getLocation());
      kryo.setInstantiatorStrategy(new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
      return kryo;
    }
  };

  private volatile EncryptionHandler encryptionHandler;

  public KryoMessageCodec(int maxMessageSize, Class<?>... messages) {
    this.maxMessageSize = maxMessageSize;
    this.messages = Arrays.asList(messages);
    this.encryptionHandler = null;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws Exception {




    if (in.readableBytes() < 4) {
      return;
    }

    in.markReaderIndex();
    int msgSize = in.readInt();
    checkSize(msgSize);

    if (in.readableBytes() < msgSize) {
      // Incomplete message in buffer.
      in.resetReaderIndex();
      return;
    }

    try {
      ByteBuffer nioBuffer = maybeDecrypt(in.nioBuffer(in.readerIndex(), msgSize));
      LOG.info("nioBuffer msgSize ------ {}",msgSize);


      java.security.ProtectionDomain pd = BaseProtocol.class.getProtectionDomain();
      CodeSource cs = pd.getCodeSource();
      LOG.info("BaseProtocol SyncJobRequest from ===========  李连杰 ======= {}",cs.getLocation());
      Input kryoIn = new Input(new ByteBufferInputStream(nioBuffer));
      LOG.info("ByteBufferInputStream(nioBuffer) Debug ===========  快手抖音 ======= {}",cs.getLocation());


      LOG.info("kryos 东北虎 ======= {}",kryos.get().getDepth());

      java.security.ProtectionDomain pdMM = org.apache.hive.spark.client.BaseProtocol.SyncJobRequest.class.getProtectionDomain();
      CodeSource csMM = pdMM.getCodeSource();
      LOG.info("李卫当官 SyncJobRequest 哈哈哈 ===========  Li ======= {}",csMM.getLocation());


      Object msg = kryos.get().readClassAndObject(kryoIn);


      java.security.ProtectionDomain pd23 = org.apache.hive.spark.client.BaseProtocol.SyncJobRequest.class.getProtectionDomain();
      CodeSource cs23 = pd23.getCodeSource();
      LOG.info("啊哈哈哈哈哈 SyncJobRequest from ===========  什么玩意二是 ======= {}",cs23.getLocation());
      if (msg != null) {
        //LOG.info("KryoMessageCodec getClass kryos name ------ {}",kryos.getClass().getName());
        out.add(msg);
      }
    } finally {
      in.skipBytes(msgSize);
    }
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf buf)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Output kryoOut = new Output(bytes);
    kryos.get().writeClassAndObject(kryoOut, msg);
    kryoOut.flush();

    byte[] msgData = maybeEncrypt(bytes.toByteArray());
    LOG.debug("Encoded message of type {} ({} bytes)", msg.getClass().getName(), msgData.length);
    checkSize(msgData.length);

    buf.ensureWritable(msgData.length + 4);
    buf.writeInt(msgData.length);
    buf.writeBytes(msgData);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (encryptionHandler != null) {
      encryptionHandler.dispose();
    }
    super.channelInactive(ctx);
  }

  private void checkSize(int msgSize) {

    java.security.ProtectionDomain StdPd = Preconditions.class.getProtectionDomain();
    CodeSource codeSource = StdPd.getCodeSource();
    LOG.info("Preconditions.checkArgument from ====msgSize === {} ======= 刘德华======= {}",msgSize,codeSource.getLocation());

    Preconditions.checkArgument(msgSize > 0, "Message size (%s bytes) must be positive.", msgSize);
    Preconditions.checkArgument(maxMessageSize <= 0 || msgSize <= maxMessageSize,
        "Message (%s bytes) exceeds maximum allowed size (%s bytes).", msgSize, maxMessageSize);
  }

  private byte[] maybeEncrypt(byte[] data) throws Exception {
    return (encryptionHandler != null) ? encryptionHandler.wrap(data, 0, data.length) : data;
  }

  private ByteBuffer maybeDecrypt(ByteBuffer data) throws Exception {
    if (encryptionHandler != null) {
      byte[] encrypted;
      int len = data.limit() - data.position();
      int offset;
      if (data.hasArray()) {
        encrypted = data.array();
        offset = data.position() + data.arrayOffset();
        data.position(data.limit());
      } else {
        encrypted = new byte[len];
        offset = 0;
        data.get(encrypted);
      }
      return ByteBuffer.wrap(encryptionHandler.unwrap(encrypted, offset, len));
    } else {
      return data;
    }
  }

  void setEncryptionHandler(EncryptionHandler handler) {
    this.encryptionHandler = handler;
  }

  interface EncryptionHandler {

    byte[] wrap(byte[] data, int offset, int len) throws IOException;

    byte[] unwrap(byte[] data, int offset, int len) throws IOException;

    void dispose() throws IOException;

  }

}
