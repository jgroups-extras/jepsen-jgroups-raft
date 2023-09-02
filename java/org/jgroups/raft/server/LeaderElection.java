package org.jgroups.raft.server;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.raft.RaftHandle;
import org.jgroups.raft.data.Request;
import org.jgroups.raft.data.Response;
import org.jgroups.util.SizeStreamable;
import org.jgroups.util.Util;

/**
 * Retrieve the leader and term. This does not receive the command through RAFT. It does something similar as
 * if it were observed externally by a client or a user implementation retrieving the information.
 *
 * @author José Bolina
 */
public class LeaderElection implements TestStateMachine {
  private static final Log log = LogFactory.getLog(LeaderElection.class);

  protected final RaftHandle raft;
  protected final JChannel ch;

  public LeaderElection(JChannel ch) {
    this.ch = ch;
    this.raft = new RaftHandle(ch, this);
  }

  @Override
  public Response receive(DataInput in) throws Exception {
    log.info("Inspecting leader!!");
    Address address = raft.leader();
    long term = raft.currentTerm();
    ElectionInspection ie = new ElectionInspection(address == null ? null : address.toString(), term);
    log.info("Inspection result: %s", ie);

    Request request = Util.objectFromStream(in);
    return new Response(request.getUuid(), ie);
  }

  @Override
  public byte[] apply(byte[] bytes, int i, int i1, boolean b) throws Exception {
    return null;
  }

  @Override
  public void readContentFrom(DataInput dataInput) throws Exception { }

  @Override
  public void writeContentTo(DataOutput dataOutput) throws Exception { }

  public static class ElectionInspection implements SizeStreamable {
    private String leader;
    private long term;

    public ElectionInspection() { }

    public ElectionInspection(String leader, long term) {
      this.leader = leader;
      this.term = term;
    }

    public String leader() {
      return leader;
    }

    public long term() {
      return term;
    }

    @Override
    public int serializedSize() {
      return Util.size(leader) + Long.BYTES;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
      Util.writeString(leader, out);
      out.writeLong(term);
    }

    @Override
    public void readFrom(DataInput in) throws IOException {
      this.leader = Util.readString(in);
      this.term = in.readLong();
    }

    @Override
    public String toString() {
      return "[" + leader + ", " + term + "]";
    }
  }
}
