# AI LLM XWiki Docker

This project provides a Docker setup for running XWiki with AI Large Language Model (LLM) capabilities.

## What's Included

- XWiki with AI LLM flavor
- MySQL or MariaDB database
- Ollama for inference

## Quick Start

1. Clone this repository
2. Navigate to the project directory
3. Run `docker compose up`

This will start XWiki, the database, and Ollama.

## Configuration

The `docker-compose.yml` file contains the main configuration for the project. Key components:

- `web`: XWiki container
- `db`: MySQL or MariaDB container
- `ollama`: Ollama inference engine container 

Note:
Ollama is configured to automatically download a default model which may take some time to become available depending on your internet connection. Currently, the default model is `qwen2` (4.4GB).
For more details on how to pull additional models or interact with the ollama image refer to the [official dockerhub ollama page](https://hub.docker.com/r/ollama/ollama).

Environment variables can be customized in the `.env` file.

## Customization

- Database: Choose between MySQL and MariaDB by using the appropriate `docker-compose.yml` file.
- XWiki Version: Set the `XWIKI_VERSION` in the Dockerfile or `.env` file.
- AI Models: Modify the Ollama section in `docker-compose.yml` to pull different models.

## Volumes

- `xwiki-data`: Persists XWiki data
- `mariadb-data`, `mysql-data` or `postgres-data`: Persists database data
- `ollama`: Persists Ollama models and data

## Ports

- XWiki: 8080
- Ollama: 11434

# How to use this image

You should first install [Docker](https://www.docker.com/) on your machine.

Then, there are several options:

1.	Pull the `xwiki` image from DockerHub.
2.	Get the [sources of this project](https://github.com/xwiki-contrib/docker-xwiki) and build them.

## Pulling an existing image

You need to run at least 2 containers:

-	One for the XWiki image
-	One for the database image to which XWiki connects to
- Ollama for inference. (Optional)

### Using `docker run`

Start by creating a dedicated docker network:

```console
docker network create -d bridge xwiki-nw
```

Then run a container for the database and make sure it's configured to use an UTF8 encoding. The following databases are supported out of the box:

-	MySQL
-	MariaDB

#### Starting MySQL

We will bind mount two local directories to be used by the MySQL container:
-	one to be used at database initialization to set permissions (see below), 
-	another to contain the data put by XWiki inside the MySQL database, so that when you stop and restart MySQL you don't find yourself without any data.

For example:
-	`/my/path/mysql-init`
-	`/my/path/mysql`

You need to make sure these directories exist, and then create a file under the `/my/path/mysql-init` directory (you can name it the way you want, for example `init.sql`), with the following content:

```sql
grant all privileges on *.* to xwiki@'%'
```

This will provide enough permissions for the `xwiki` user to create new schemas which is required to be able to create sub-wikis. 

Note: Make sure the directories you are mounting into the container are fully-qualified, and aren't relative paths.

```console
docker run --net=xwiki-nw --name mysql-xwiki -v /my/path/mysql:/var/lib/mysql -v /my/path/mysql-init:/docker-entrypoint-initdb.d -e MYSQL_ROOT_PASSWORD=xwiki -e MYSQL_USER=xwiki -e MYSQL_PASSWORD=xwiki -e MYSQL_DATABASE=xwiki -d mysql:9.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin --explicit-defaults-for-timestamp=1
```

You should adapt the command line to use the passwords that you wish for the MySQL root password and for the `xwiki` user password (make sure to also change the GRANT command).

Notes:

-   The `explicit-defaults-for-timestamp` parameter was introduced in MySQL 5.6.6 and will thus work only for that version and beyond. If you are using an older MySQL version, please use the following instead:

    ```console
    docker run --net=xwiki-nw --name mysql-xwiki -v /my/path/mysql:/var/lib/mysql -v /my/path/mysql-init:/docker-entrypoint-initdb.d -e MYSQL_ROOT_PASSWORD=xwiki -e MYSQL_USER=xwiki -e MYSQL_PASSWORD=xwiki -e MYSQL_DATABASE=xwiki -d mysql:9.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin
    ```

#### Starting MariaDB

This is exactly similar to starting MySQL and you should thus follow exactly the same steps as for MySQL. The only thing to change is the docker image for MariaDB: instead of `mysql:<tag>`, use `mariadb:<tag>`. For example: `mariadb:11.4`.

Full command example:

```console
docker run --net=xwiki-nw --name mysql-xwiki -v /my/path/mariadb:/var/lib/mysql -v /my/path/mariadb-init:/docker-entrypoint-initdb.d -e MYSQL_ROOT_PASSWORD=xwiki -e MYSQL_USER=xwiki -e MYSQL_PASSWORD=xwiki -e MYSQL_DATABASE=xwiki -d mariadb:11.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin --explicit-defaults-for-timestamp=1
```

#### Starting PostgreSQL

We will bind mount a local directory to be used by the PostgreSQL container to contain the data put by XWiki inside the database, so that when you stop and restart PostgreSQL you don't find yourself without any data. For example:

- `/my/path/postgres`

You need to make sure this directory exists, before proceeding.

Note Make sure the directory you specify is specified with the fully-qualified path, not a relative path.

```
docker run --net=xwiki-nw --name postgres-xwiki -v /my/path/postgres:/var/lib/postgresql/data -e POSTGRES_ROOT_PASSWORD=xwiki -e POSTGRES_USER=xwiki -e POSTGRES_PASSWORD=xwiki -e POSTGRES_DB=xwiki -e POSTGRES_INITDB_ARGS="--encoding=UTF8" -d postgres:16
```

You should adapt the command line to use the passwords that you wish for the PostgreSQL root password and for the xwiki user password.

#### Starting XWiki with the AI-LLM flavor setup

We will also bind mount a local directory for the XWiki permanent directory (contains application config and state), for example:

-	`/my/path/xwiki`

Note Make sure the directory you specify is specified with the fully-qualified path, not a relative path.

Ensure this directory exists, and then run XWiki in a container by issuing one of the following command.

For MySQL:

```console
docker run --net=xwiki-nw --name xwiki -p 8080:8080 -v /my/path/xwiki:/usr/local/xwiki -e DB_USER=xwiki -e DB_PASSWORD=xwiki -e DB_DATABASE=xwiki -e DB_HOST=mysql-xwiki ai-llm:0.6.2-16.6.0-mysql-tomcat
```

For MariaDB:

```console
docker run --net=xwiki-nw --name xwiki -p 8080:8080 -v /my/path/xwiki:/usr/local/xwiki -e DB_USER=xwiki -e DB_PASSWORD=xwiki -e DB_DATABASE=xwiki -e DB_HOST=postgres-xwiki ai-llm:0.6.2-16.6.0-mariadb-tomcat
```

For PostgreSQL:
```console
docker run --net=xwiki-nw --name xwiki -p 8080:8080 -v /my/path/xwiki:/usr/local/xwiki -e DB_USER=xwiki -e DB_PASSWORD=xwiki -e DB_DATABASE=xwiki -e DB_HOST=postgres-xwiki ai-llm:0.6.2-16.6.0-postgres-tomcat
```

Be careful to use the same DB username, password and database names that you've used on the first command to start the DB container. Also, please don't forget to add a `-e DB_HOST=` environment variable with the name of the previously created DB container so that XWiki knows where its database is.

At this point, XWiki should start in interactive blocking mode, allowing you to see logs in the console. Should you wish to run it in "detached mode", just add a "-d" flag in the previous command.

```console
docker run -d --net=xwiki-nw ...
```

### Using `docker-compose`

Another solution is to use the Docker Compose files we provide.

#### For MySQL on Tomcat

-	`wget https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mysql-tomcat/mysql/init.sql`: This will download some SQL to execute at startup for MySQL
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mysql-tomcat/mysql/init.sql -o init.sql`
-	`wget -O docker-compose.yml https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mysql-tomcat/docker-compose.yml`
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mysql-tomcat/docker-compose.yml`
-	`wget https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mysql-tomcat/.env`: This contains default configuration values you should edit (version of XWiki to use, etc)
	 -	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mysql-tomcat/.env -o .env`
-	`docker compose up`

#### For MariaDB on Tomcat

-	`wget https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mariadb-tomcat/mariadb/init.sql`: This will download some SQL to execute at startup for MariaDB
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mariadb-tomcat/mariadb/init.sql -o init.sql`
-	`wget -O docker-compose.yml https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mariadb-tomcat/docker-compose.yml`
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/mariadb-tomcat/docker-compose.yml -o docker-compose.yml`
-	`wget https://raw.githubusercontent.com/xwiki-contrib/docker-xwiki/master/16/mariadb-tomcat/.env`: This contains default configuration values you should edit (version of XWiki to use, etc)
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/docker-xwiki/master/16/mariadb-tomcat/.env -o .env`
-	`docker compose up`

#### For PostgreSQL on Tomcat

-	`wget -O docker-compose.yml https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/postgres-tomcat/docker-compose.yml`
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/16/postgres-tomcat/docker-compose.yml -o docker-compose.yml`
-	`wget https://raw.githubusercontent.com/xwiki-contrib/docker-xwiki/master/16/postgres-tomcat/.env`: This contains default configuration values you should edit (version of XWiki to use, etc)
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/docker-xwiki/master/16/postgres-tomcat/.env -o .env`
-	`docker compose up`

## Building

To build the Docker image locally:
-	Install Git and run `git clone https://github.com/xwiki-contrib/ai-llm.git` or download the sources from the GitHub UI. Then go to the directory corresponding to the docker tag you wish to use. For example: `cd docker/16/mysql-tomcat`
- Navigate to the directory containing the Dockerfile (e.g., `16/mysql-tomcat` or `16/mariadb-tomcat`)
-	Run `docker compose up`
-	Start a browser and point it to `http://localhost:8080`

Note that if you want to set a custom version of XWiki you can edit the `.env` file and set the values you need in there. It's also possible to override them on the command line with `docker-compose run -e "XWIKI_VERSION=12.10.10"`.

Note that `docker compose up` will automatically build the XWiki image on the first run. If you need to rebuild it you can issue `docker compose up --build`. You can also build the image with `docker build . -t xwiki-mysql-tomcat:latest` for example.

You can also just build the image by issuing `docker build -t xwiki-ai-llm .` and then use the instructions from above to start XWiki and the database using `docker run ...`.

## Details for the xwiki image
This is based on the official the official xwiki docker repository.
For more configuration options and other information you can refer to the [xwik-docker documentation](https://github.com/xwiki/xwiki-docker/).