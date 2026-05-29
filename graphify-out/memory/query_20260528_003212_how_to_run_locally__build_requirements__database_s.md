---
type: "query"
date: "2026-05-28T00:32:12.464383+00:00"
question: "how to run locally, build requirements, database setup, server configuration"
contributor: "graphify"
source_nodes: ["build.gradle", "dev.gradle", "docker-compose", "Dockerfile"]
---

# Q: how to run locally, build requirements, database setup, server configuration

## Answer

Expanded from original query via vocab: [build, run, local, database, server, config, setup, gradle, docker, migration, properties, schema]. Two paths to run locally: (1) Docker Compose (easiest): docker-compose up --build — spins MySQL 8.0 on port 3308 and Tomcat 10.1 on port 8085. (2) Manual/Tomcat: set app_home in build.properties to your Tomcat root, then gradle build or gradle deploy -Penv=prod. Key configs: openspecimen.properties (datasource.url, datasource.username/password, app.home), Tomcat context XML with JNDI DataSource, Java 17 + Node 20 required.

## Source Nodes

- build.gradle
- dev.gradle
- docker-compose
- Dockerfile