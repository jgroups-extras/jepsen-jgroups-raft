#!/bin/bash

# We add our hostname to the shared volume, so that control can find us
echo "Adding hostname to shared volume" >> /var/log/jepsen-setup.log
# We do a little dance to get our hostname (random hex), IP, then use DNS to
# get a proper container name.
if ! grep -q `hostname` /var/jepsen/shared/nodes; then
  echo `hostname` >> /var/jepsen/shared/nodes
fi

# We make sure that root's authorized keys are ready
echo "Setting up root's authorized_keys" >> /var/log/jepsen-setup.log
mkdir /root/.ssh
chmod 700 /root/.ssh
cp /run/secrets/authorized_keys /root/.ssh/
chmod 600 /root/.ssh/authorized_keys
