package org.jgroups.raft.client;

import java.io.DataInput;
import java.net.ConnectException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.jgroups.Address;
import org.jgroups.blocks.cs.Receiver;
import org.jgroups.blocks.cs.TcpClient;
import org.jgroups.raft.data.Request;
import org.jgroups.raft.data.Response;
import org.jgroups.raft.server.Server;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

/**
 * A client for the replicated state machine.
 * <p>
 * This client provides the API to issue the requests to the replicated state machine. The client
 * sends the request and blocks, waiting for the response.
 * <p>
 * This implementation is based on {@link ReplicatedStateMachineClient}.
 */
public class SyncReplicatedStateMachineClient implements Receiver, AutoCloseable {

  private final String name;
  private final Map<UUID, CompletableFuture<String>> requests = new ConcurrentHashMap<>();
  private long timeout;
  private InetAddress server;
  private int serverPort;
  private volatile TcpClient client;

  public SyncReplicatedStateMachineClient(String name) {
    this.timeout = 5_000;
    this.name = name;
  }

  public SyncReplicatedStateMachineClient withTimeout(long timeout) {
    this.timeout = timeout;
    return this;
  }

  public SyncReplicatedStateMachineClient withTargetAddress(InetAddress server) {
    this.server = server;
    return this;
  }

  public SyncReplicatedStateMachineClient withTargetPort(int port) {
    this.serverPort = port;
    return this;
  }

  public void start() {
    if (client != null) throw new IllegalStateException("Client already created!");

    client = new TcpClient(null, 0, server, serverPort);
    client.receiver(this);
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
    Object r = Util.objectFromStream(in);
    if (r instanceof Exception ex) {
      ex.printStackTrace();
      return;
    }

    if (r != null) {
      assert r instanceof Response : "Response type not accepted: " + r.getClass();
      Response res = (Response) r;
      CompletableFuture<String> cf = requests.remove(res.getUuid());
      if (cf == null) {
        throw new IllegalStateException("Request not found: " + res.getUuid());
      }

      if (res.isFailure()) {
        cf.completeExceptionally(res.getFailure());
      } else {
        cf.complete(res.getResponse());
      }
    }
  }

  private String operation(UUID req, ByteArrayDataOutputStream out) throws Throwable {
    try {
      assertConnected();
      CompletableFuture<String> cf = requests.get(req);
      if (cf == null) throw new IllegalStateException("Request is null before sending");

      client.send(out.buffer(), 0, out.position());
      return cf.get(timeout, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      Throwable re = extract(e);
      if (re != null) throw re;
      throw e;
    }
  }

  @Override
  public void receive(Address sender, ByteBuffer buf) {
    Util.bufferToArray(sender, buf, this);
  }

  public void put(long key, long value) throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(Server.Command.PUT.ordinal());
    Util.objectToStream(String.valueOf(key), out);
    Util.objectToStream(new Request(uuid, String.valueOf(value)), out);
    operation(uuid, out);
  }

  public String get(long key, boolean quorum) throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(Server.Command.GET.ordinal());
    Util.objectToStream(String.valueOf(key), out);
    Util.objectToStream(uuid, out);
    out.writeBoolean(quorum);
    return operation(uuid, out);
  }

  public boolean compareAndSet(long key, long from, long to) throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(Server.Command.CAS.ordinal());
    Util.objectToStream(String.valueOf(key), out);
    Util.objectToStream(String.valueOf(from), out);
    Util.objectToStream(String.valueOf(to), out);
    Util.objectToStream(uuid, out);
    String cas = operation(uuid, out);
    return Boolean.parseBoolean(cas);
  }

  private UUID prepareRequest() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    UUID uuid = UUID.randomUUID();
    while (requests.containsKey(uuid)) uuid = UUID.randomUUID();
    requests.put(uuid, cf);

    return uuid;
  }

  @Override
  public void close() {
    System.out.printf("Stopping client at [%s:%d]\n", server, serverPort);
    if (client != null) {
      client.stop();
    }

    client = null;
  }

  private static Throwable extract(Throwable t) {
    Throwable c = t;
    while (c instanceof ExecutionException && c.getCause() != null) {
      c = c.getCause();
    }

    return c == null ? t : c;
  }

  private void assertConnected() throws Exception {
    if (client.isConnected()) return;

    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeout);
    // We want the sleep time to increase in arithmetic progression
    // 30 loops with the default timeout of 30 seconds means the initial wait is ~ 65 millis
    int loops = 30;
    int progressionSum = loops * (loops + 1) / 2;
    long initialSleepNanos = timeoutNanos / progressionSum;
    long sleepNanos = initialSleepNanos;
    long expectedEndTime = System.nanoTime() + timeoutNanos;
    while (expectedEndTime - System.nanoTime() > 0) {
      client.start();

      if (client.isConnected())
        return;

      LockSupport.parkNanos(sleepNanos);
      sleepNanos += initialSleepNanos;
    }
    throw new ConnectException("Failed connecting client to " + server + ":" + serverPort);
  }
}
