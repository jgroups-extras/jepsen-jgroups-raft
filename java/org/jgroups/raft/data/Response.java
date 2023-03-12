package org.jgroups.raft.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.jgroups.util.SizeStreamable;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

public class Response implements SizeStreamable {
  private UUID uuid;
  private String response;
  private Throwable failure;

  public Response() { }

  public Response(UUID uuid, String response) {
    this.uuid = uuid;
    this.response = response;
    this.failure = null;
  }

  public Response(UUID uuid, Throwable failure) {
    this.uuid = uuid;
    this.response = null;
    this.failure = failure;
  }

  public UUID getUuid() {
    return uuid;
  }

  public String getResponse() {
    return response;
  }

  public Throwable getFailure() {
    return failure;
  }

  @Override
  public int serializedSize() {
    return Util.size(uuid) + Byte.BYTES + Util.size(response);
  }

  @Override
  public void writeTo(DataOutput out) throws IOException {
    Util.objectToStream(uuid, out);
    boolean success = failure == null;
    out.writeBoolean(success);
    if (success) Util.objectToStream(response, out);
    else Util.objectToStream(failure, out);
  }

  @Override
  public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
    this.uuid = Util.objectFromStream(in);
    boolean success = in.readBoolean();
    if (success) {
      this.response = Util.objectFromStream(in);
      this.failure = null;
    } else {
      this.response = null;
      this.failure = Util.objectFromStream(in);
    }
  }

  public boolean isFailure() {
    return failure != null;
  }
}
