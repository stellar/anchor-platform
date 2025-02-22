#!/usr/bin/env bash
echo "Stopping all docker containers"
docker stop $(docker ps -aq)
echo "Removing all docker containers"
docker rm $(docker ps -aq)
echo "Prune unused docker networks"
docker network prune