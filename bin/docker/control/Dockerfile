FROM debian:bullseye
MAINTAINER jake@apache.org

ENV LEIN_ROOT true

RUN apt-get -y -q update && \
    apt-get install -qy openjdk-17-jdk-headless \
    libjna-java \
    git \
    pssh \
    curl \
    wget \
    gnuplot \
    graphviz \
    dos2unix \
    dnsutils

RUN wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    mv lein /usr/bin && \
    chmod +x /usr/bin/lein && \
    lein self-install

COPY jepsen /jepsen/
RUN if [ -f /jepsen/project.clj ]; then cd /jepsen && lein install; fi

ADD ./bashrc /root/.bashrc
ADD ./init.sh /init.sh
RUN dos2unix /init.sh /root/.bashrc \
    && chmod +x /init.sh

CMD /init.sh
