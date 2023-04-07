package org.jgroups.raft.server;

import java.io.DataInput;

import org.jgroups.JChannel;
import org.jgroups.blocks.atomic.SyncCounter;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.raft.Options;
import org.jgroups.raft.blocks.CounterService;
import org.jgroups.raft.data.Response;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

public class ReplicatedCounter extends CounterService implements TestStateMachine {

  protected final Log log = LogFactory.getLog(getClass());

  public ReplicatedCounter(JChannel ch) {
    super(ch);
    // By default, we want a linearizable counter.
    allow_dirty_reads = false;
  }

  public Response receive(DataInput in) throws Exception {
    RequestType type = RequestType.values()[in.readByte()];
    String name = Util.readString(in);
    UUID uuid = Util.objectFromStream(in);

    return switch (type) {
      case GET -> {
        SyncCounter counter = getOrCreateCounter(name, 0L).sync();
        yield new Response(uuid, counter.get());
      }
      case ADD -> {
        SyncCounter counter = getOrCreateCounter(name, 0L)
            .withOptions(Options.create(true))
            .sync();
        counter.addAndGet(in.readLong());
        yield new Response(uuid, (Object) null);
      }
      case ADD_AND_GET -> {
        SyncCounter counter = getOrCreateCounter(name, 0L).sync();
        long value = in.readLong();
        long result = counter.addAndGet(value);
        log.info("ADDING AND GET: (%s) -> (%s)", value, result);
        yield new Response(uuid, result);
      }
      case COMPARE_AND_SET -> {
        SyncCounter counter = getOrCreateCounter(name, 0L).sync();
        long expected = in.readLong();
        long value = in.readLong();
        boolean result = counter.compareAndSet(expected, value);
        log.info("CAS: (%s) -> (%s)? %s", expected, value, result);
        yield new Response(uuid, result ? 1L : 0L);
      }
    };
  }

  public enum RequestType {
    GET,
    ADD,
    ADD_AND_GET,
    COMPARE_AND_SET,
  }
}
