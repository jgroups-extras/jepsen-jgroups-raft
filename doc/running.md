# Running the tests

We describe how to run the tests with the different setups we provide.

## Docker & Docker Compose

This setup will start four containers using docker-compose. One node is the control, and the other
three are workers. The necessary dependencies are installed for the control node with the test
included. Everything is ready to run.

From the project root execute:

```bash
./bin/up
```

This command should create the SSH keys, loads it to the nodes, and start and install everything.
It should be ready to run once it stops logging, should finish with something like:

```text
jepsen-control  | Welcome to Jepsen on Docker
jepsen-control  | ===========================
jepsen-control  | 
jepsen-control  | Please run `docker exec -it jepsen-control bash` in another terminal to proceed.
jepsen-n1       | 
jepsen-n1       | Debian GNU/Linux 11 n1 console
jepsen-n1       | 
jepsen-n3       | 
jepsen-n3       | Debian GNU/Linux 11 n3 console
jepsen-n3       | 
jepsen-n2       | 
jepsen-n2       | Debian GNU/Linux 11 n2 console
jepsen-n2       | 
```
Note the final output may not be identical. The next step is connecting to the control node and
executing the tests. To enter into the control node, open another terminal and execute:

```bash
docker exec -it jepsen-control bash
```

When connected into the control node, we should be greeted with:

```text
Welcome to Jepsen on Docker
===========================

This container runs the Jepsen tests in sub-containers.

You are currently in the base dir of the git repo for Jepsen.
If you modify the core jepsen library make sure you "lein install" it so other tests can access.

To run a test:
   lein run test --nodes-file ~/nodes
root@control:/jepsen#
```

We should be in folder containing the test, the last step is to run it. Since we have three nodes,
we need to tell that to Jepsen while running, execute:

```bash
lein run test --nodes-file ~/nodes
```

This should be all to running the tests!

### Troubleshooting

If the tests fail to connect to the worker nodes, we may need to add the workers to the known hosts.
First, check if the known host file is empty:

```bash
cat ~/.ssh/known_hosts
```

If the output is empty, then we execute the following to add the nodes to the file:

```bash
while read node; do
  ssh-keyscan -t rsa $node >> ~/.ssh/known_hosts
done <~/nodes
```
