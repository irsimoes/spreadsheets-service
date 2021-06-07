#!/bin/bash

if [ "$#" -ne 1 ]; then 
echo "usage: start-kafka.sh localhost or start-kafka.sh kafka"
exit 1
fi

[ ! "$(docker network ls | grep sd-net )" ] && \
	docker network create --driver=bridge --subnet=172.20.0.0/16 sdnet

docker pull nunopreguica/sd2021-kafka

echo "Launching Kafka Server: "  $1

docker rm -f kafka

docker run -h $1  \
           --name=kafka \
	   --network=sdnet \
           --rm -t  -p 9092:9092 -p 2181:2181 nunopreguica/sd2021-kafka
