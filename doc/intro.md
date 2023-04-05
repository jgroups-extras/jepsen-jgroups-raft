# Introduction to jepsen.jgroups.raft

We designed a test using the Jepsen library and sample applications using the JGroups RAFT. We evaluated version
1.0.10.Final with JGroups 5.0.12.Final on a five-node Debian Bullseye cluster. The failures include network
partition, which isolates single nodes and the leader, a majority split, and overlapping majorities in a ring.
We crashed and paused leaders, minorities, and single nodes. Since our implementation has membership operations,
we have a nemesis for adding and removing random nodes.

The membership changes work as a human operator would do. First, SSH into the remote node and then execute the
CLI command for adding or removing a node. We only work within the set of configured nodes.

## Workloads

For testing purposes, we have workloads for (single and multiple) registers and counters. For each workload, we
developed a client application. These applications are not available in the library.

### Registers

Registers use a HashMap, replicated using the RAFT implementation. This implementation uses the Jepsen to generate
the keys and apply the operations. The operations include reading, writing, and compare-and-swap. We check the
operation history using the Knossos linearizability checker.

#### Single Register

The single register is a single key-value pair. The key is always `0`. The value is a random integer on [0-5) interval.

**Network partitions**

We have different network partitions, isolating nodes, majorities, minorities, and a ring. We ran the test with
varying configurations, trying to cover more scenarios.

In the first configuration, three nodes, 90 seconds, and five threads with five requests per second. We then
increased from three to five nodes and the concurrency to 10. We have a request timeout longer than the network
partition lasts. Executing the tests, we didn't find any problems. The system operates correctly, and after the
network heals, the requests resume or time out. When timing out, we can't tell if the operation succeeded.
Polluting the execution history with timeouts can create too much pressure on the model checker, making it
unfeasible to verify.

So, the following configuration focuses on timing out requests. We run the same setup with five nodes but with
a concurrency of five. This execution has the network taking longer to heal, a longer time than the request timeout.
The results have more "crashed" processes, but the system still operates correctly and does not violate linearizability.


### Counters

TBD.
