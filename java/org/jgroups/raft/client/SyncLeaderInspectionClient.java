package org.jgroups.raft.client;


import clojure.lang.IPersistentVector;
import clojure.lang.Tuple;
import org.jgroups.raft.data.Request;
import org.jgroups.raft.server.LeaderElection;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

/**
 * @author José Bolina
 */
public class SyncLeaderInspectionClient extends SyncClient<LeaderElection.ElectionInspection> {

  public SyncLeaderInspectionClient(String name) {
    super(name);
  }

  public IPersistentVector inspect() throws Throwable {
    UUID uuid = prepareRequest();
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
    Util.objectToStream(new Request(uuid, ""), out);
    LeaderElection.ElectionInspection ei = operation(uuid, out);
    return Tuple.create(ei.leader(), ei.term());
  }

  @Override
  public String toString() {
    return "Client to -> " + name;
  }
}
