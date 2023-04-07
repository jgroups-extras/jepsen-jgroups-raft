package org.jgroups.raft.client;

import org.jgroups.raft.data.Request;
import org.jgroups.raft.server.Server;
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
public class SyncReplicatedStateMachineClient extends SyncClient<String> {

  public SyncReplicatedStateMachineClient(String name) {
    super(name);
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
}
