FROM node:20-bookworm-slim AS ui-builder

WORKDIR /ui
COPY ui/package*.json /ui/
RUN npm ci --legacy-peer-deps
COPY ui /ui
RUN npm run build

FROM gradle:7.5.1-jdk17 AS builder

WORKDIR /workspace
COPY --chown=gradle:gradle . /workspace
COPY --from=ui-builder --chown=gradle:gradle /ui/dist /workspace/ui/dist

RUN set -eux; \
    gradle --no-daemon clean create_war \
      -x vue_backup_src \
      -x vue_install_dependencies \
      -x vue_restore_src \
      -x vue_build; \
    WAR_PATH="$(find /workspace/build/libs -maxdepth 1 -type f -name '*.war' | head -n1)"; \
    test -n "${WAR_PATH}"; \
    if [ "${WAR_PATH}" != "/workspace/build/libs/openspecimen.war" ]; then \
      cp "${WAR_PATH}" /workspace/build/libs/openspecimen.war; \
    fi

FROM tomcat:10.1-jdk17-temurin AS runtime

ARG MYSQL_CONNECTOR_VERSION=8.4.0

ENV APP_HOME=/opt/openspecimen \
    DB_HOST=db \
    DB_PORT=3306 \
    DB_NAME=openspecimen \
    DB_USER=os_user \
    DB_PASS=os_password \
    DATASOURCE_TYPE=fresh \
    DATASOURCE_JNDI=jdbc/openspecimen \
    DATASOURCE_MAX_TOTAL=100 \
    DATASOURCE_MAX_IDLE=30

RUN apt-get update && \
    apt-get install -y --no-install-recommends bash curl ca-certificates netcat-openbsd gosu && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${APP_HOME}/logs" \
             "${APP_HOME}/plugins" \
             "${APP_HOME}/ichat" \
             "${APP_HOME}/bulk-import" \
             "${CATALINA_HOME}/conf/Catalina/localhost"

RUN curl -fsSL -o "${CATALINA_HOME}/lib/mysql-connector-j.jar" \
    "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/${MYSQL_CONNECTOR_VERSION}/mysql-connector-j-${MYSQL_CONNECTOR_VERSION}.jar"

COPY --from=builder /workspace/build/libs/openspecimen.war ${CATALINA_HOME}/webapps/openspecimen.war

RUN cat > /entrypoint.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

wait_for_db() {
  echo "[entrypoint] waiting for MySQL at ${DB_HOST}:${DB_PORT} ..."
  for _ in $(seq 1 120); do
    if nc -z "${DB_HOST}" "${DB_PORT}" >/dev/null 2>&1; then
      echo "[entrypoint] MySQL socket reachable"
      return 0
    fi
    sleep 2
  done
  echo "[entrypoint] ERROR: MySQL not reachable after timeout"
  return 1
}

xml_escape() {
  local s="$1"
  s="${s//&/&amp;}"
  s="${s//\"/&quot;}"
  s="${s//</&lt;}"
  s="${s//>/&gt;}"
  printf "%s" "$s"
}

wait_for_db

JDBC_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

cat > "${APP_HOME}/openspecimen.properties" <<PROPS
database.type=mysql
datasource.type=${DATASOURCE_TYPE}
datasource.jndi=${DATASOURCE_JNDI}
datasource.url=${JDBC_URL}
datasource.username=${DB_USER}
datasource.password=${DB_PASS}
datasource.driverClass=com.mysql.cj.jdbc.Driver
app.home=${APP_HOME}
app.data_dir=${APP_HOME}
plugin.dir=${APP_HOME}/plugins
ichat.dir=${APP_HOME}/ichat
bulk.import.dir=${APP_HOME}/bulk-import
PROPS

cat > "${CATALINA_HOME}/conf/openspecimen.properties" <<PROPS
database.type=mysql
datasource.type=${DATASOURCE_TYPE}
datasource.jndi=${DATASOURCE_JNDI}
app.home=${APP_HOME}
app.data_dir=${APP_HOME}
plugin.dir=${APP_HOME}/plugins
ichat.dir=${APP_HOME}/ichat
bulk.import.dir=${APP_HOME}/bulk-import
PROPS

DB_USER_XML="$(xml_escape "${DB_USER}")"
DB_PASS_XML="$(xml_escape "${DB_PASS}")"
JDBC_URL_XML="$(xml_escape "${JDBC_URL}")"

cat > "${CATALINA_HOME}/conf/Catalina/localhost/openspecimen.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<Context>
  <Resource name="${DATASOURCE_JNDI}"
            auth="Container"
            type="javax.sql.DataSource"
            username="${DB_USER_XML}"
            password="${DB_PASS_XML}"
            driverClassName="com.mysql.cj.jdbc.Driver"
            url="${JDBC_URL_XML}"
            maxTotal="${DATASOURCE_MAX_TOTAL}"
            maxIdle="${DATASOURCE_MAX_IDLE}"/>
  <Environment name="config/openspecimen"
               value="${APP_HOME}/openspecimen.properties"
               type="java.lang.String"/>
</Context>
XML

export CATALINA_OPTS="${CATALINA_OPTS:-} -Dapp.home=${APP_HOME} -Dapp.properties=${APP_HOME}/openspecimen.properties"

echo "[entrypoint] fixing runtime permissions ..."
chown -R openspecimen:openspecimen "${APP_HOME}" || true

echo "[entrypoint] starting Tomcat as openspecimen ..."
exec gosu openspecimen catalina.sh run
EOF

RUN chmod +x /entrypoint.sh

RUN set -eux; \
    groupadd -r openspecimen; \
    useradd -r -g openspecimen -d "${APP_HOME}" -s /usr/sbin/nologin openspecimen; \
    chown -R openspecimen:openspecimen \
      "${APP_HOME}" \
      "${CATALINA_HOME}/conf" \
      "${CATALINA_HOME}/logs" \
      "${CATALINA_HOME}/temp" \
      "${CATALINA_HOME}/work" \
      "${CATALINA_HOME}/webapps"

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=120s --retries=12 \
  CMD curl -fsS http://127.0.0.1:8080/openspecimen/ui-app/ >/dev/null || exit 1

ENTRYPOINT ["/entrypoint.sh"]
