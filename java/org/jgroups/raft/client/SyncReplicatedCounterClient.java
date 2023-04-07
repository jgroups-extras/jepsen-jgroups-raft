package org.jgroups.raft.client;

import java.util.Objects;

import org.jgroups.raft.server.ReplicatedCounter;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

public class SyncReplicatedCounterClient extends SyncClient<Long> {
  private static final String MY_TEST_COUNTER = "mtc";

  public SyncReplicatedCounterClient(String name) {
    super(name);
  }


  public long get() throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(ReplicatedCounter.RequestType.GET.ordinal());
    Util.writeString(MY_TEST_COUNTER, out);
    Util.objectToStream(uuid, out);
    return Objects.requireNonNull(operation(uuid, out), "Get response can never be null");
  }

  public void add(long delta) throws Throwable {
    addOperation(delta, ReplicatedCounter.RequestType.ADD);
  }

  public long addAndGet(long delta) throws Throwable {
    return addOperation(delta, ReplicatedCounter.RequestType.ADD_AND_GET);
  }

  private long addOperation(long delta, ReplicatedCounter.RequestType type) throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(type.ordinal());
    Util.writeString(MY_TEST_COUNTER, out);
    Util.objectToStream(uuid, out);
    out.writeLong(delta);

    return switch (type) {
      case ADD -> {
        operation(uuid, out);
        yield 0;
      }
      case ADD_AND_GET -> Objects.requireNonNull(operation(uuid, out), "AddAndGet response can never be null");
      default -> throw new IllegalStateException("Unexpected value: " + type);
    };
  }

  public boolean compareAndSet(long expected, long value) throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    out.writeByte(ReplicatedCounter.RequestType.COMPARE_AND_SET.ordinal());
    Util.writeString(MY_TEST_COUNTER, out);
    Util.objectToStream(uuid, out);
    out.writeLong(expected);
    out.writeLong(value);
    return operation(uuid, out) != 0L;
  }
}
