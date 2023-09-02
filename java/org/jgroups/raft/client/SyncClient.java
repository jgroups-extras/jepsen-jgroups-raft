package org.jgroups.raft.client;

import org.jgroups.Address;
import org.jgroups.blocks.cs.Receiver;
import org.jgroups.blocks.cs.TcpClient;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.raft.data.Response;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

import java.io.DataInput;
import java.net.ConnectException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class SyncClient<T> implements Receiver, AutoCloseable {
  protected final Log log = LogFactory.getLog(getClass());
  protected final String name;
  private final Map<UUID, CompletableFuture<T>> requests = new ConcurrentHashMap<>();

  private long timeout;
  private InetAddress server;
  private int serverPort;
  private volatile TcpClient client;

  public SyncClient(String name) {
    this.timeout = 5_000;
    this.name = name;
  }

  public SyncClient<T> withTimeout(long timeout) {
    this.timeout = timeout;
    return this;
  }

  public SyncClient<T> withTargetAddress(InetAddress server) {
    this.server = server;
    return this;
  }

  public SyncClient<T> withTargetPort(int port) {
    this.serverPort = port;
    return this;
  }

  public void start() {
    log.info("Starting client: %s", name);
    if (client != null) throw new IllegalStateException("Client already created!");

    client = new TcpClient(null, 0, server, serverPort);
    client.receiver(this);
  }

  protected UUID prepareRequest() {
    CompletableFuture<T> cf = new CompletableFuture<>();
    UUID uuid = UUID.randomUUID();
    while (requests.containsKey(uuid)) uuid = UUID.randomUUID();
    requests.put(uuid, cf);

    return uuid;
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
      CompletableFuture<T> cf = requests.remove(res.getUuid());
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

  protected T operation(UUID req, ByteArrayDataOutputStream out) throws Throwable {
    try {
      assertConnected();
      CompletableFuture<T> cf = requests.get(req);
      if (cf == null) throw new IllegalStateException("Request is null before sending");

      client.send(out.buffer(), 0, out.position());
      return cf.get(timeout, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      Throwable t = handleThrowable(e);
      log.error("[%s] Exception while sending request: %s", name, t.getMessage());
      throw t;
    }
  }

  @Override
  public void close() {
    log.info("Stopping client at [%s:%d]", server, serverPort);
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
      tryConnect();

      if (client.isConnected())
        return;

      LockSupport.parkNanos(sleepNanos);
      sleepNanos += initialSleepNanos;
    }

    tryConnect();
  }

  private void tryConnect() throws Exception {
    try {
      client.start();
    } catch (ConnectException e) {
      ConnectException ce = new ConnectException("Failed connecting client to " + server + ":" + serverPort);
      ce.addSuppressed(e);
      throw ce;
    }
  }

  private Throwable handleThrowable(Throwable t) {
    Throwable re = extract(t);
    if (re != null) return re;
    return t;
  }

  private static Throwable extract(Throwable t) {
    Throwable c = t;
    while (c instanceof ExecutionException && c.getCause() != null) {
      c = c.getCause();
    }

    return c == null ? t : c;
  }
}
