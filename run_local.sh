# !/bin/bash
# run in local environment
if [ ! -d "./logs/" ]
then
  mkdir ./logs/
fi
nohup redis-server > logs/redis-logs &
nohup /usr/local/Cellar/kafka/2.4.1/bin/zookeeper-server-start /usr/local/etc/kafka/zookeeper.properties > logs/zookeeper-logs &
nohup /usr/local/Cellar/kafka/2.4.1/bin/kafka-server-start /usr/local/Cellar/kafka/2.4.1/libexec/config/server.properties > logs/kafka-logs &
nohup java -jar springbackend/target/springbackend-0.0.1-SNAPSHOT.jar > logs/springbackend-logs &