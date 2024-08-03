#!/bin/bash

docker rm xwiki-mysql-tomcat-web
docker rm xwiki-mysql-db
docker rm ollama
docker rm llamafile
docker rm mysql-tomcat-ollama-model-pull-1
docker volume rm mysql-tomcat_mysql-data
docker volume rm mysql-tomcat_xwiki-data
docker volume rm ollama
docker volume rm mysql-tomcat_ollama
docker rmi -f xwiki:16.3.0-mysql-tomcat
docker rmi -f mysql-tomcat-web:latest
docker rmi -f mysql:8.3
docker rmi -f mysql-tomcat-llamafile:latest