package org.jgroups.raft.server;

import java.io.DataInput;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.blocks.cs.BaseServer;
import org.jgroups.blocks.cs.Receiver;
import org.jgroups.blocks.cs.TcpServer;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.raft.RAFT;
import org.jgroups.protocols.raft.Role;
import org.jgroups.raft.demos.ReplicatedStateMachineDemo;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.Util;

/**
 * A replicated state machine using {@link RAFT}.
 * <p>
 * This is deployed in the remote nodes and is our entry point to testing our {@link RAFT} implementation.
 * This is designed to test a replicated {@link java.util.Map}, which must be consistent across all nodes.
 * Here consistency means that all nodes must hold the same subset of data. The operations we test are:
 * <ul>
 *   <li>{@link Server.Command#PUT}: Maps a key to a value and returns null.</li>
 *   <li>{@link Server.Command#GET}: Retrieve the value mapped to the key.</li>
 *   <li>{@link Server.Command#CAS}. Compare-and-set the key returns a boolean indicating if the
 *    operation succeeded.</li>
 * </ul>
 *
 * This implementation is based on {@link ReplicatedStateMachineDemo}.
 *
 * @author José Bolina
 */
public class Server implements Receiver, AutoCloseable, RAFT.RoleChange {
  protected final Log log = LogFactory.getLog(getClass());
  private String props;
  private String name;
  private long timeout;

  private JChannel channel;
  private TestStateMachine stateMachine;
  private BaseServer server;


  @Override
  public void receive(Address sender, byte[] buf, int offset, int length) {
    try (ByteArrayDataInputStream in = new ByteArrayDataInputStream(buf, offset, length)) {
      receive(sender, in);
    } catch (Exception e) {
      log.error("Error receiving data from %s", sender, e);
    }
  }

  @Override
  public void receive(Address address, DataInput in) throws Exception {
    sendResponse(address, stateMachine.receive(in));
  }

  @Override
  public void close() throws Exception {
    server.close();
    channel.close();
    channel = null;
  }

  public Server withProps(String props) {
    if (props == null || props.trim().isEmpty()) {
      log.error("Properties are empty!");
      return this;
    }

    log.info("Using properties at: " + props);
    this.props = props;
    return this;
  }

  public Server withName(String name) {
    this.name = name;
    System.getProperties().put("raft_id", name);
    return this;
  }

  public Server withTimeout(long timeout) {
    this.timeout = timeout;
    return this;
  }

  public Server withMembers(String members) {
    log.info("Setting members: " + members);
    System.getProperties().put("raft_members", members);
    return this;
  }

  public Server prepareReplicatedMapStateMachine() throws Exception {
    if (channel != null) throw new IllegalStateException("Channel is already running");
    channel = new JChannel(props).name(name);
    stateMachine = new ReplicatedMap<String, String>(channel);
    ((ReplicatedMap<String, String>) stateMachine)
        .raftId(name)
        .timeout(timeout)
        .addRoleChangeListener(this);
    return this;
  }

  public Server prepareCounterStateMachine() throws Exception {
    if (channel != null) throw new IllegalStateException("Channel is already running");
    channel = new JChannel(props).name(name);
    stateMachine = new ReplicatedCounter(channel);
    ((ReplicatedCounter) stateMachine)
        .raftId(name)
        .replTimeout(timeout)
        .addRoleChangeListener(this);
    return this;
  }

  public Server prepareElectionInspection() throws Exception {
    if (channel != null) throw new IllegalStateException("Channel is already running");
    channel = new JChannel(props).name(name);
    stateMachine = new LeaderElection(channel);
    return this;
  }

  public Server start(InetAddress bind, int port) throws Exception {
    Objects.requireNonNull(channel, "Channel is null");
    Objects.requireNonNull(stateMachine, "State machine is null");

    try {
      log.info("Connecting %s with members %s", name, System.getProperty("raft_members"));
      channel.connect("rsm");
    } catch (Exception e) {
      log.error("Error connecting to channel", e);
      throw e;
    }

    Util.registerChannel(channel, "rsm");
    server = new TcpServer(bind, port).receiver(this);
    server.start();
    int local_port=server.localAddress() instanceof IpAddress ? ((IpAddress)server.localAddress()).getPort(): 0;
    log.info("Listening at %s:%s", bind != null ? bind : "0.0.0.0",  local_port);

    return this;
  }



  private void sendResponse(Address target, Object res) {
    try {
      byte[] buf = Util.objectToByteBuffer(res);
      server.send(target, buf, 0, buf.length);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Throwable extractCause(Throwable t) {
    Throwable c = t.getCause();
    while (c instanceof ExecutionException && c.getCause() != null)
      c = t.getCause();

    return c == null ? t : c;
  }

  @Override
  public void roleChanged(Role role) {
    log.info("Changed role to " + role);
  }

  public enum Command {
    PUT,
    GET,
    CAS,
  }
}
