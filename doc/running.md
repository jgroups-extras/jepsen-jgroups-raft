# Running the tests

We describe how to run the tests with the different setups we provide.

## Linux Containers

To run using Linux Containers, we required [LXD](https://linuxcontainers.org/lxd/introduction/).
And in addition to that, Jepsen's requirements:

- A [JVM](https://openjdk.java.net/install/) version 21 or higher.
- JNA, so the JVM can talk to your SSH.
- [Leiningen](https://leiningen.org/): a Clojure build tool.
- [Gnuplot](http://www.gnuplot.info/): how Jepsen renders performance plots.
- [Graphviz](https://graphviz.org/): how Jepsen renders transactional anomalies.

The host is the control node. The control node is responsible for running the tests.

Now we are going to prepare the workers. Then, let's put some of the `lxc` to use.
The first step, start a Debian 13 (Trixie) node.
Unfortunately, LXC remote image repository was phased-down ([More here](https://discuss.linuxcontainers.org/t/important-notice-for-lxd-users-image-server/18479)).
To install an image, we will retrieve the build from LXC Jenkins and add it locally.
Some list of links:

* LXC Jenkins: [https://jenkins.linuxcontainers.org/](https://jenkins.linuxcontainers.org/)
* LXC Debian builds: [https://jenkins.linuxcontainers.org/job/image-debian/](https://jenkins.linuxcontainers.org/job/image-debian/)
* JGroups Raft recommended image: [AMD64 Trixie default](https://jenkins.linuxcontainers.org/job/image-debian/architecture=amd64,release=trixie,variant=default/)

Download the files `incus.tar.xz` and `rootfs.squashfs` to the same folder and execute:

```bash
lxc image import incus.tar.xz rootfs.squashfs --alias custom-debian-trixie
```

This should create an image hosted locally with the name `custom-debian-trixie`.
We can proceed to the configuration now.
Let's create the first node running our image:

```bash
lxc launch custom-debian-trixie node-1
```

We have the first node running. The next step is configuring SSH on the running worker for
the control node to access it. To enter into the worker and prepare SSH, run:

```bash
lxc exec node-1 bash

# Now running on node-1 bash, as root user.
apt-get install openssh-server
sed -i "s/#PermitRootLogin prohibit-password/PermitRootLogin yes/g" /etc/ssh/sshd_config
service sshd start
```

With that, we have a running SSH service on the worker. Going back to the control node,
let's prepare the keys. On the project root, execute:

```bash
ssh-keygen -t rsa -N "" ./bin/secret/id_rsa
```

This created the public and private keys on the control node. Be careful that running the Docker
setup might override this key. Now, we only need to transfer the public key to the worker. Do the following:

```bash
lxc exec node-1 mkdir /root/.ssh
lxc file push ./bin/secret/id_rsa.pub node-1/root/.ssh/authorized_keys
lxc exec node-1 chown root:root /root/.ssh/authorized_keys
```

This should leave us with a ready worker node. Back on the control node, SSH into the worker by running:

```bash
ssh -i ./bin/secret/id_rsa root@node-1
```

Notice that we need to point to the generated key. Now, to run the test, we need to create additional workers.
The good news is that we can copy, and we can do that by executing:

```bash
lxc copy node-1 node-2
lxc start node-2
```

To leave everything ready to run the test, go to the control node and SSH into each worker to add it to
the known hosts. Then proceed to run the test:

```bash
lein run test --node node-1 --node node-2 --ssh-private-key ./bin/secret/id_rsa --time-limit 60 --concurrency 100
```

On the first run, workers download more dependencies, so it might take some time. If the test times out starting,
you can execute the command again.


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
