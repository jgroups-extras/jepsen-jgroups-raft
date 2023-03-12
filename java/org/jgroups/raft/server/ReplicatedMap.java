package org.jgroups.raft.server;

import java.util.concurrent.TimeUnit;

import org.jgroups.JChannel;
import org.jgroups.raft.blocks.ReplicatedStateMachine;
import org.jgroups.raft.exception.KeyNotFoundException;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.Util;

public class ReplicatedMap<K, V> extends ReplicatedStateMachine<K, V> {
  public static final byte GET = 3;
  public static final byte CAS = 4;

  public ReplicatedMap(JChannel ch) {
    super(ch);
  }

  @Override
  public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
    ByteArrayDataInputStream in = new ByteArrayDataInputStream(data, offset, length);
    byte command = in.readByte();

    switch (command) {
      case PUT -> {
        K key = Util.objectFromStream(in);
        V val = Util.objectFromStream(in);
        V old;
        synchronized (map) {
          old = map.put(key, val);
        }

        return old == null ? null
            : serialize_response ? Util.objectToByteBuffer(old) : null;
      }
      case GET -> {
        K key = Util.objectFromStream(in);
        synchronized (map) {
          return Util.objectToByteBuffer(map.get(key));
        }
      }
      case CAS -> {
        K key = Util.objectFromStream(in);
        V from = Util.objectFromStream(in);
        V to = Util.objectFromStream(in);
        synchronized (map) {
          V res = map.compute(key, (ignore, curr) -> {
            if (curr == null) {
              throw new KeyNotFoundException(key);
            }

            return curr.equals(from)
                ? to
                : curr;
          });
          return Util.objectToByteBuffer(res == to);
        }
      }
      default -> throw new IllegalStateException(String.format("Unknown command: %d", command));
    }
  }

  public V quorumGet(K key) throws Exception {
    return invoke(GET, key, null, false);
  }

  public boolean compareAndSet(K key, V from, V to) throws Exception {
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream(256);
    out.writeByte(CAS);
    Util.objectToStream(key, out);
    Util.objectToStream(from, out);
    Util.objectToStream(to, out);

    byte[] buf = out.buffer();
    byte[] rsp = raft.set(buf, 0, out.position(), repl_timeout, TimeUnit.MILLISECONDS);
    return Util.objectFromByteBuffer(rsp);
  }
}
