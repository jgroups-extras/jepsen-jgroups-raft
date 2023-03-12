package org.jgroups.raft.exception;

public class KeyNotFoundException extends RuntimeException {
  private final Object key;

  public KeyNotFoundException(Object key) {
    super(String.format("Key '%s' not found", key));
    this.key = key;
  }

  public String getCode() {
    return "KEY_NOT_FOUND";
  }

  public Object getKey() {
    return key;
  }
}
