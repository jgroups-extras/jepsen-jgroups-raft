package org.jgroups.raft.client;

import java.io.DataInput;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import org.jgroups.Address;
import org.jgroups.blocks.cs.Receiver;
import org.jgroups.blocks.cs.TcpClient;
import org.jgroups.raft.data.Request;
import org.jgroups.raft.data.Response;
import org.jgroups.raft.exception.KeyNotFoundException;
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
    this.timeout = 15_000;
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

  public void start() throws Exception {
    if (client != null) throw new IllegalStateException("Client already created!");

    client = new TcpClient(null, 0, server, serverPort);
    client.receiver(this);
    client.start();
    System.out.printf("Connected %s to [%s:%d]\n", name, server, serverPort);

    assertConnected();
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

  private Future<String> operation(ThrowingFunction<UUID, ByteArrayDataOutputStream> buffer) throws Exception {
    UUID uuid = UUID.randomUUID();
    CompletableFuture<String> cf = new CompletableFuture<>();
    while (requests.containsKey(uuid)) uuid = UUID.randomUUID();
    requests.put(uuid, cf);

    ByteArrayDataOutputStream out = buffer.execute(uuid);

    try {
      assertConnected();
      client.send(out.buffer(), 0, out.position());
      return cf;
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void receive(Address sender, ByteBuffer buf) {
    Util.bufferToArray(sender, buf, this);
  }

  public void put(String key, long value) throws Exception {
    operation(uuid -> {
      ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
      out.writeByte(Server.Command.PUT.ordinal());
      Util.objectToStream(key, out);
      Util.objectToStream(new Request(uuid, String.valueOf(value)), out);
      return out;
    }).get(timeout, TimeUnit.MILLISECONDS);
  }

  public String get(String key) throws Exception {
    return operation(uuid -> {
      ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
      out.writeByte(Server.Command.GET.ordinal());
      Util.objectToStream(key, out);
      Util.objectToStream(uuid, out);
      return out;
    }).get(timeout, TimeUnit.MILLISECONDS);
  }

  public boolean compareAndSet(String key, long from, long to) throws Exception {
    try {
      String cas = operation(uuid -> {
        ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
        out.writeByte(Server.Command.CAS.ordinal());
        Util.objectToStream(key, out);
        Util.objectToStream(String.valueOf(from), out);
        Util.objectToStream(String.valueOf(to), out);
        Util.objectToStream(uuid, out);
        return out;
      }).get(timeout, TimeUnit.MILLISECONDS);
      return Boolean.parseBoolean(cas);
    } catch (ExecutionException e) {
      Throwable c = e;
      while (c instanceof ExecutionException && c.getCause() != null) {
        c = c.getCause();
      }
      if (c instanceof KeyNotFoundException knfe) throw knfe;
      throw e;
    }
  }

  @Override
  public void close() {
    System.out.printf("Stopping client at [%s:%d]\n", server, serverPort);
    if (client != null) {
      client.stop();
    }

    client = null;
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
    assert client.isConnected() : "Failed connecting client to " + server + ":" + serverPort;
  }

  public static void main(String[] args) throws Exception {
    var rsm = new SyncReplicatedStateMachineClient("local")
        .withTargetAddress(InetAddress.getByName("127.0.0.1"))
        .withTargetPort(9000);
    rsm.start();
    while (true) {
      Scanner scanner = new Scanner(System.in);
      String opt = scanner.nextLine();
      if (opt.startsWith("quit")) break;

      if (opt.startsWith("put")) {
        String k = scanner.nextLine();
        long v = scanner.nextLong();
        try {
          rsm.put(k, v);
        } catch (Exception e) {
          e.printStackTrace();
        }
        continue;
      }

      if (opt.startsWith("get")) {
        String k = scanner.nextLine();
        try {
          System.out.println(k + " GOT? " + rsm.get(k));;
        } catch (Exception e) {
          e.printStackTrace();
        }
      }

      if (opt.startsWith("cas")) {
        String k = scanner.nextLine();
        long from = scanner.nextLong();
        long to = scanner.nextLong();
        try {
          System.out.println(k + " CASED? " + rsm.compareAndSet(k, from, to));
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    rsm.close();
  }

  private interface ThrowingFunction<I, O> extends Function<I, O> {
    @Override
    default O apply(I i) {
      try {
        return execute(i);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    O execute(I i) throws Exception;
  }
}
