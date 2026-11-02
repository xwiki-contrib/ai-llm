# AI LLM XWiki Docker

This project provides a Docker setup for running XWiki with AI Large Language Model (LLM) capabilities.

## What's Included

- XWiki with AI LLM flavor
- MySQL, MariaDB or PosteresSQL database
- Ollama for inference

## Quick Start

1. Clone this repository
2. Navigate to the directory of the docker version you want to use (ex: `ai-llm/docker/17/mariadb-tomcat/` )
3. Run `docker compose up`

This will start XWiki, the database, and Ollama.

## Configuration

The `docker-compose.yml` file contains the main configuration for the project. Key components:

- `web`: XWiki container
- `db`: MySQL or MariaDB container
- `ollama`: Ollama inference engine container 

Note:
Ollama is configured to automatically download a default model which may take some time to become available depending on your internet connection. Currently, the default model is `gemma4:e4b` (6.6GB).
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
-	PostgresSQL

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
docker run --net=xwiki-nw --name mysql-xwiki -v /my/path/mysql:/var/lib/mysql -v /my/path/mysql-init:/docker-entrypoint-initdb.d -e MYSQL_ROOT_PASSWORD=xwiki -e MYSQL_USER=xwiki -e MYSQL_PASSWORD=xwiki -e MYSQL_DATABASE=xwiki -d mysql:26 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin --explicit-defaults-for-timestamp=1
```

You should adapt the command line to use the passwords that you wish for the MySQL root password and for the `xwiki` user password (make sure to also change the GRANT command).

Notes:

-   The `explicit-defaults-for-timestamp` parameter was introduced in MySQL 5.6.6 and will thus work only for that version and beyond. If you are using an older MySQL version, please use the following instead:

    ```console
    docker run --net=xwiki-nw --name mysql-xwiki -v /my/path/mysql:/var/lib/mysql -v /my/path/mysql-init:/docker-entrypoint-initdb.d -e MYSQL_ROOT_PASSWORD=xwiki -e MYSQL_USER=xwiki -e MYSQL_PASSWORD=xwiki -e MYSQL_DATABASE=xwiki -d mysql:26 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin
    ```

#### Starting MariaDB

This is exactly similar to starting MySQL and you should thus follow exactly the same steps as for MySQL. The only thing to change is the docker image for MariaDB: instead of `mysql:<tag>`, use `mariadb:<tag>`. For example: `mariadb:13`.

Full command example:

```console
docker run --net=xwiki-nw --name mysql-xwiki -v /my/path/mariadb:/var/lib/mysql -v /my/path/mariadb-init:/docker-entrypoint-initdb.d -e MYSQL_ROOT_PASSWORD=xwiki -e MYSQL_USER=xwiki -e MYSQL_PASSWORD=xwiki -e MYSQL_DATABASE=xwiki -d mariadb:13 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin --explicit-defaults-for-timestamp=1
```

#### Starting PostgreSQL

We will bind mount a local directory to be used by the PostgreSQL container to contain the data put by XWiki inside the database, so that when you stop and restart PostgreSQL you don't find yourself without any data. For example:

- `/my/path/postgres`

You need to make sure this directory exists, before proceeding.

Note Make sure the directory you specify is specified with the fully-qualified path, not a relative path.

```
docker run --net=xwiki-nw --name postgres-xwiki -v /my/path/postgres:/var/lib/postgresql -e POSTGRES_USER=xwiki -e POSTGRES_PASSWORD=xwiki -e POSTGRES_DB=xwiki -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale-provider=builtin --locale=C.UTF-8" -d postgres:18
```

You should adapt the command line to use the passwords that you wish for the PostgreSQL root password and for the xwiki user password.

#### Starting XWiki with the AI-LLM flavor setup

We will also bind mount a local directory for the XWiki permanent directory (contains application config and state), for example:

-	`/my/path/xwiki`

Note Make sure the directory you specify is specified with the fully-qualified path, not a relative path.

Ensure this directory exists, and then run XWiki in a container by issuing one of the following command.

For MySQL:

```console
docker run --net=xwiki-nw --name xwiki -p 8080:8080 -v /my/path/xwiki:/usr/local/xwiki -e DB_USER=xwiki -e DB_PASSWORD=xwiki -e DB_DATABASE=xwiki -e DB_HOST=mysql-xwiki xwikicontrib/ai-llm:1.0.0-17.10.14-mysql-tomcat
```

For MariaDB:

```console
docker run --net=xwiki-nw --name xwiki -p 8080:8080 -v /my/path/xwiki:/usr/local/xwiki -e DB_USER=xwiki -e DB_PASSWORD=xwiki -e DB_DATABASE=xwiki -e DB_HOST=mariadb-xwiki xwikicontrib/ai-llm:1.0.0-17.10.14-mariadb-tomcat
```

For PostgreSQL:
```console
docker run --net=xwiki-nw --name xwiki -p 8080:8080 -v /my/path/xwiki:/usr/local/xwiki -e DB_USER=xwiki -e DB_PASSWORD=xwiki -e DB_DATABASE=xwiki -e DB_HOST=postgres-xwiki xwikicontrib/ai-llm:1.0.0-17.10.14-postgres-tomcat
```

Be careful to use the same DB username, password and database names that you've used on the first command to start the DB container. Also, please don't forget to add a `-e DB_HOST=` environment variable with the name of the previously created DB container so that XWiki knows where its database is.

At this point, XWiki should start in interactive blocking mode, allowing you to see logs in the console. Should you wish to run it in "detached mode", just add a "-d" flag in the previous command.

```console
docker run -d --net=xwiki-nw ...
```

### Using `docker-compose`

Another solution is to use the Docker Compose files we provide.

#### For MySQL on Tomcat

-	`mkdir -p mysql && wget -O mysql/init.sql https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mysql-tomcat/mysql/init.sql`: This will download some SQL to execute at startup for MySQL. The `docker-compose.yml` mounts it from the `mysql/` subdirectory; without it MySQL only grants the `xwiki` user rights on its own database, which is not enough for XWiki to create subwikis or to run its schema migrations.
	-	If you don't have `wget` or prefer to use `curl`: `mkdir -p mysql && curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mysql-tomcat/mysql/init.sql -o mysql/init.sql`
-	`wget -O docker-compose.yml https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mysql-tomcat/docker-compose.yml`
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mysql-tomcat/docker-compose.yml`
-	`wget https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mysql-tomcat/.env`: This contains default configuration values you should edit (version of XWiki to use, etc)
	 -	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mysql-tomcat/.env -o .env`
-	`docker compose up`

#### For MariaDB on Tomcat

-	`mkdir -p mariadb && wget -O mariadb/init.sql https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mariadb-tomcat/mariadb/init.sql`: This will download some SQL to execute at startup for MariaDB. The `docker-compose.yml` mounts it from the `mariadb/` subdirectory; without it MariaDB only grants the `xwiki` user rights on its own database, which is not enough for XWiki to create subwikis or to run its schema migrations.
	-	If you don't have `wget` or prefer to use `curl`: `mkdir -p mariadb && curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mariadb-tomcat/mariadb/init.sql -o mariadb/init.sql`
-	`wget -O docker-compose.yml https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mariadb-tomcat/docker-compose.yml`
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mariadb-tomcat/docker-compose.yml -o docker-compose.yml`
-	`wget https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mariadb-tomcat/.env`: This contains default configuration values you should edit (version of XWiki to use, etc)
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/mariadb-tomcat/.env -o .env`
-	`docker compose up`

#### For PostgreSQL on Tomcat

-	`wget -O docker-compose.yml https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/postgres-tomcat/docker-compose.yml`
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/postgres-tomcat/docker-compose.yml -o docker-compose.yml`
-	`wget https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/postgres-tomcat/.env`: This contains default configuration values you should edit (version of XWiki to use, etc)
	-	If you don't have `wget` or prefer to use `curl`: `curl -fSL https://raw.githubusercontent.com/xwiki-contrib/ai-llm/main/docker/17/postgres-tomcat/.env -o .env`
-	`docker compose up`

## Upgrading from the `docker/16` setup

The `docker/16` Compose files (XWiki 16.6.0, AI LLM 0.6.2) and the `docker/17` ones use the same directory names, so when you start the new stack from a checkout of this repository Docker Compose reuses the existing `xwiki-data`, database and `ollama` volumes. Your pages, the AI collections and the pulled models are kept; XWiki migrates its database schema on the first start and the Distribution Wizard then offers the new AI LLM flavor version as an upgrade.

Before you start the new stack, note the following per database.

### MariaDB and MySQL: apply the global grant first

The `docker/16` Compose files mounted `./init.sql`, a path at which the file never existed, so the grant from `init.sql` was never applied on those deployments: the `xwiki` user only has rights on the `xwiki` database. XWiki 16 ran fine like this, but the schema migration to 17.10 fails with `Access denied; you need (at least one of) the PROCESS privilege(s)`.

Apply the grant as root on the running `docker/16` stack before switching (adapt the root password and the user name if you changed them in `.env`):

```console
# MariaDB
docker compose exec db mariadb -uroot -pxwiki -e "GRANT ALL PRIVILEGES ON *.* TO 'xwiki'@'%'; FLUSH PRIVILEGES;"
# MySQL
docker compose exec db mysql -uroot -pxwiki -e "GRANT ALL PRIVILEGES ON *.* TO 'xwiki'@'%'; FLUSH PRIVILEGES;"
```

If you already started the new stack and XWiki failed with the error above, run the same command against the new `db` container and then `docker compose restart web`.

- MariaDB 11.3 to 13: the new Compose file sets `MARIADB_AUTO_UPGRADE=1`, so the data directory is upgraded automatically on the first start. This path has been tested end to end (schema migration, flavor upgrade, collections and chat intact).
- MySQL 8.3 to 26: not tested yet. MySQL only supports in-place upgrades along its documented upgrade paths, so if the new container refuses to start on the old data directory, either step through the intermediate images (`mysql:8.4`, then the following LTS releases) with the same volume, or dump the database from the old stack and restore it into a fresh volume as described for PostgreSQL below.

### PostgreSQL: dump and restore

PostgreSQL cannot upgrade a data directory across major versions, and the official `postgres:18` image also changed where it keeps its data (the volume is now mounted on `/var/lib/postgresql` instead of `/var/lib/postgresql/data`). Starting the new stack on the old volume therefore does not fail: PostgreSQL 18 initialises an empty cluster next to the old data and XWiki shows an empty wiki. This path has not been tested yet; the procedure is the standard one:

1. On the running `docker/16` stack, dump the database: `docker compose exec -T db pg_dump -U xwiki -Fc xwiki > xwiki.dump`
2. Stop it with `docker compose down`, then remove the old database volume (keep the dump!): `docker volume rm postgres-tomcat_postgres-data`
3. From `docker/17/postgres-tomcat`, start only the database: `docker compose up -d db`
4. Restore the dump: `docker compose exec -T db pg_restore -U xwiki -d xwiki --no-owner --role=xwiki < xwiki.dump`
5. Start the whole stack with `docker compose up`

### Ollama

The pulled models stay in the `ollama` volume. The new Compose files pull `gemma4:e4b` instead of `qwen2`. AI LLM 1.0.1 and later no longer preconfigure the `qwen2`, `gpt-4o` and `gpt-4o-mini` model pages, so the flavor upgrade offers to delete them (the Distribution Wizard asks first); accept, or keep `qwen2` pulled if you still use that page.

## Building

To build the Docker image locally:
-	Install Git and run `git clone https://github.com/xwiki-contrib/ai-llm.git` or download the sources from the GitHub UI. Then go to the directory corresponding to the docker tag you wish to use. For example: `cd docker/17/mysql-tomcat`
- Navigate to the directory containing the Dockerfile (e.g., `17/mysql-tomcat` or `17/mariadb-tomcat`)
-	Run `docker build . -t xwikicontrib/ai-llm:1.0.0-17.10.14-mysql-tomcat` (use the tag referenced by the `docker-compose.yml` of that directory, so that `docker compose up` picks up the local image instead of pulling the published one)
-	Run `docker compose up`
-	Start a browser and point it to `http://localhost:8080`

Note that if you want to set a custom version of XWiki you can edit the `.env` file and set the values you need in there. It's also possible to override them on the command line with `docker compose run -e "XWIKI_VERSION=17.10.14"`.

You can also just build the image by issuing `docker build -t xwiki-ai-llm .` and then use the instructions from above to start XWiki and the database using `docker run ...`.

## Details for the xwiki image
This is based on the official the official xwiki docker repository.
For more configuration options and other information you can refer to the [xwik-docker documentation](https://github.com/xwiki/xwiki-docker/).
