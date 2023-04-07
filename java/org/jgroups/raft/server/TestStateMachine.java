package org.jgroups.raft.server;

import java.io.DataInput;

import org.jgroups.raft.StateMachine;
import org.jgroups.raft.data.Response;

public interface TestStateMachine extends StateMachine {

  Response receive(DataInput in) throws Exception;
}
