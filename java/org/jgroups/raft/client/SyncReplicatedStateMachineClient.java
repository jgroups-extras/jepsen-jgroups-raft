package org.jgroups.raft.client;

import java.io.DataInput;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jgroups.Address;
import org.jgroups.blocks.cs.Receiver;
import org.jgroups.blocks.cs.TcpClient;
import org.jgroups.raft.demos.ReplicatedStateMachineDemo;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.Util;

public class SyncReplicatedStateMachineClient implements Receiver, AutoCloseable {


  private final BlockingQueue<CompletableFuture<Long>> request = new LinkedBlockingQueue<>(1);
  private long timeout;
  private TcpClient client;

  public SyncReplicatedStateMachineClient() {
    this.timeout = 15_000;
  }

  public SyncReplicatedStateMachineClient timeout(long timeout) {
    this.timeout = timeout;
    return this;
  }

  public synchronized void start(InetAddress host, int port) throws Exception {
    if (client != null) throw new IllegalStateException("Client already created!");

    System.out.println("Connecting to " + host + ":" + port);
    client = new TcpClient(null, 0, host, port);
    client.receiver(this);
    try {
      client.start();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void receive(Address sender, byte[] buf, int offset, int length) {
    ByteArrayDataInputStream in = new ByteArrayDataInputStream(buf, offset, length);
    try {
      receive(sender, in);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void receive(Address address, DataInput in) throws Exception {
    Object res = Util.objectFromStream(in);
    if (res instanceof Exception ex) {
      ex.printStackTrace();
      return;
    }

    if (res != null) {
      CompletableFuture<Long> cf = request.poll();
      if (cf != null) {
        cf.complete(Long.valueOf((String) res));
      }
    }

  }

  @Override
  public void receive(Address sender, ByteBuffer buf) {
    Util.bufferToArray(sender, buf, this);
  }

  public void put(String key, long value) throws Exception {
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(ReplicatedStateMachineDemo.Command.PUT.ordinal());
    Util.objectToStream(key, out);
    Util.objectToStream(String.valueOf(value), out);
    client.send(out.buffer(), 0, out.position());
  }

  public long get(String key) throws Exception {
    CompletableFuture<Long> cf = new CompletableFuture<>();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(ReplicatedStateMachineDemo.Command.GET.ordinal());
    Util.objectToStream(key, out);

    request.put(cf);

    client.send(out.buffer(), 0, out.position());
    return cf.get(timeout, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void close() {
    if (client != null) client.stop();
    client = null;
  }
}
