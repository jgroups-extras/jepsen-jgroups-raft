package org.jgroups.raft.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.jgroups.util.SizeStreamable;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

public class Request implements SizeStreamable {
  private UUID uuid;
  private String value;

  public Request() { }

  public Request(UUID uuid, String value) {
    this.uuid = uuid;
    this.value = value;
  }

  public UUID getUuid() {
    return uuid;
  }

  public String getValue() {
    return value;
  }

  @Override
  public int serializedSize() {
    return Util.size(uuid) + Util.size(value);
  }

  @Override
  public void writeTo(DataOutput out) throws IOException {
    Util.objectToStream(uuid, out);
    Util.writeString(value, out);
  }

  @Override
  public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
    this.uuid = Util.objectFromStream(in);
    this.value = Util.readString(in);
  }

  @Override
  public String toString() {
    return "Payload{" +
        "uuid=" + uuid +
        ", value='" + value + '\'' +
        '}';
  }
}
