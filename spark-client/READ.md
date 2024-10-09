SparkClientFactory
-----> RpcServer-----> SparkClientImpl
-----> this.driverRpc = rpcServer.registerClient(sessionid, secret, protocol).get();
----->注册返回Rpc driverRpc
-----> 任务提交submit（提交协议ClientProtocol），提交的JobId是UUID值。 /**
* Send an RPC call to the remote endpoint and returns a future that can be used to monitor the
* operation.
  */
  public Future<Void> call(Object msg) {
  return call(msg, Void.class);
  }
  call的实现逻辑
  public <T> Future<T> call(final Object msg, Class<T> retType) {
  Preconditions.checkArgument(msg != null);
  LOG.info("channel metadata =======网红  ============= {} ",channel.metadata().toString());
  Preconditions.checkState(channel.isActive(), "RPC channel is closed.");
  try {
  final long id = rpcId.getAndIncrement();
  final Promise<T> promise = createPromise();
  final ChannelFutureListener listener = new ChannelFutureListener() {
  @Override
  public void operationComplete(ChannelFuture cf) {
  if (!cf.isSuccess() && !promise.isDone()) {
  LOG.warn("Failed to send RPC, closing connection.", cf.cause());
  promise.setFailure(cf.cause());
  dispatcher.discardRpc(id);
  close();
  }
  }
  };

  dispatcher.registerRpc(id, promise, msg.getClass().getName());
  channel.eventLoop().submit(new Runnable() {
  @Override
  public void run() {
  channel.write(new MessageHeader(id, Rpc.MessageType.CALL)).addListener(listener);
  channel.writeAndFlush(msg).addListener(listener);
  }
  });
  return promise;
  } catch (Exception e) {
  throw Throwables.propagate(e);
  }
  }
  ------>
