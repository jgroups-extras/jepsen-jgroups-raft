Ansible Provisioning
======================

Provision the resources to run the Jepsen test in a cloud environment.

Requirements
------------

The controller node requires Ansible.
The nodes provisioned by Ansible require sshd and the correct keys uploaded.

Playbooks
---------

There are multiple playbooks to help manage dependencies, files, start and stop the test.

### stop.yml

This playbook will stop the Java process running on all nodes.

```bash
$ ansible-playbook -i inventory.yaml stop.yml
```

EC2 Provision
-------------

It is also now possible to run this benchmark on AWS EC2 machines automatically.

To do this you must install the required AWS EC2 roles
- Run command `ansible-galaxy collection install -r roles/ec2-requirements.yml`

Now all that is needed is to set your EC2 Secret Key environment variables
- `export AWS_ACCESS_KEY_ID=<Key Id>`
- `export AWS_SECRET_ACCESS_KEY=<Key>`

### aws-ec2.yml

This will provision and manage instances.
The required arguments are the region (e.g `-e region=us-east-2`) and operation (e.g `-e operation=create`).
* The operations supported are `create` and `delete`.
    * `create` - required to be ran first. This creates the configured instances, a local ssh file, and a local inventory file to interact with them.
    * `delete` - deletes all the instances, security group, ssh key (remote and local) and the local inventory

Note after doing `create` the working directory will contain two new files:
* `benchmark_${user}_${region}.pem` file is the private ssh to key to connect to the EC2 machines
* `benchmark_${user}_${region}_inventory.yaml`

All the instances defaults are found at [main.yaml](roles/aws_ec2/defaults/main.yml).
You can override these values via normal ansible means https://docs.ansible.com/ansible/latest/playbook_guide/playbooks_variables.html#variable-precedence-where-should-i-put-a-variable.

You can run this playbook with:

```bash
$ ansible-playbook -i , aws-ec2.yml -e operation=create -e region=sa-east-1 
```

> [!IMPORTANT]
> The empty inventory argument **must** be provided.

License
-------

Apache License, Version 2.0
