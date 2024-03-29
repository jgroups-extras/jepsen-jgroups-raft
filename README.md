# jepsen.jgroups.raft

Jepsen tests for the [JGroups RAFT](https://github.com/belaban/jgroups-raft) implementation.

# Setup

A Jepsen environment is required before running. In short, a cluster of at least three
nodes and a control node to run the tests. The control node runs the suite and requires
JDK, JNA, Leiningen, Gnuplot, and Graphviz.

## Setup

We provide different setups so the test can be reproducible by anyone interested.
Each will have requirements to run. We provide:

* [Linux Containers](/doc/running.md#linux-containers)
* [Docker & Docker Compose](/doc/running.md#docker--docker-compose)

For development purposes, we recommend running with LXC.

## Running

We do not enter into Jepsen's configuration, so we explain only the options available
for our tests. We provide workloads for testing the state machine replication and
counter. The available nemesis is none.

# Test Suite

The reasoning behind our tests and the results we found are listed in the [docs](/doc/intro.md).
