package org.jgroups.raft.server;

import java.io.DataInput;
import java.net.InetAddress;
import java.nio.ByteBuffer;
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
import org.jgroups.raft.data.Request;
import org.jgroups.raft.data.Response;
import org.jgroups.raft.demos.ReplicatedStateMachineDemo;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

/**
 * A replicated state machine using {@link RAFT}.
 * <p>
 * This is deployed in the remote nodes and is our entry point to testing our {@link RAFT} implementation.
 * This is designed to test a replicated {@link java.util.Map}, which must be consistent across all nodes.
 * Here consistency means that all nodes must hold the same subset of data. Some nodes might fall behind,
 * but they should be able the catch-up. The operations we test are:
 * <ul>
 *   <li>{@link Server.Command#PUT}: Maps a key to a value and returns null.</li>
 *   <li>{@link Server.Command#GET}: Retrieve the value mapped to the key.</li>
 *   <li>{@link Server.Command#CAS}. Compare-and-set the key returns a boolean indicating if the
 *    operation succeeded and may throw an exception if the key does not exist.</li>
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
  private ReplicatedMap<String, String> rsm;
  private BaseServer server;


  @Override
  public void receive(Address sender, byte[] buf, int offset, int length) {
    try (ByteArrayDataInputStream in = new ByteArrayDataInputStream(buf, offset, length)) {
      receive(sender, in);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void receive(Address sender, ByteBuffer buf) {
    Util.bufferToArray(sender, buf, this);
  }

  @Override
  public void receive(Address address, DataInput in) throws Exception {
    int ordinal = in.readByte();
    String key = Util.objectFromStream(in);

    Response res = switch (Command.values()[ordinal]) {
      case PUT -> {
        Request request = Util.objectFromStream(in);
        log.info("PUT: %s --> %s", key, request);
        put(key, request.getValue());
        yield new Response(request.getUuid(), (String) null);
      }
      case GET -> {
        log.info("GET: " + key);
        UUID uuid = Util.objectFromStream(in);
        boolean quorum = in.readBoolean();
        yield quorum ? new Response(uuid, rsm.quorumGet(key)) : new Response(uuid, rsm.get(key));
      }
      case CAS -> {
        String from = Util.objectFromStream(in);
        String to = Util.objectFromStream(in);
        UUID uuid = Util.objectFromStream(in);
        try {
          boolean cas = rsm.compareAndSet(key, from, to);
          log.info("CAS: %s (%s) -> (%s)? %s", key, from, to, cas);
          yield new Response(uuid, String.valueOf(cas));
        } catch (Exception e) {
          log.info("CAS: %s unknown key", key);
          yield new Response(uuid, extractCause(e));
        }
      }
    };

    sendResponse(address, res);
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
    return this;
  }

  public Server withTimeout(long timeout) {
    this.timeout = timeout;
    return this;
  }

  public Server withMembers(String members) {
    log.info("-- setting members: " + members);
    System.getProperties().put("raft_members", members);
    return this;
  }

  public Server start(InetAddress bind, int port) throws Exception {
    if (channel != null) throw new IllegalStateException("Channel is already running");

    channel = new JChannel(props).name(name);
    rsm = new ReplicatedMap<>(channel);
    rsm.raftId(name).timeout(timeout);
    channel.connect("rsm");
    Util.registerChannel(rsm.channel(), "rsm");
    rsm.addRoleChangeListener(this);

    server = new TcpServer(bind, port).receiver(this);
    server.start();
    int local_port=server.localAddress() instanceof IpAddress ? ((IpAddress)server.localAddress()).getPort(): 0;
    log.info("Listening at %s:%s", bind != null ? bind : "0.0.0.0",  local_port);

    return this;
  }

  private void put(String key, String value) {
    try {
      rsm.put(key, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void sendResponse(Address target, Object res) {
    try {
      byte[] buf = Util.objectToByteBuffer(res);
      server.send(target, buf, 0, buf.length);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Throwable extractCause(Throwable t) {
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
