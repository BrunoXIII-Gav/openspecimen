package com.krishagni.catissueplus.core.common.access;

import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Locale;

import org.junit.Test;

import liquibase.Liquibase;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.database.OfflineConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import static org.junit.Assert.*;

public class ProtocolContentMigrationTest {

	@Test
	public void migrationIsAdditiveAndProducesValidMysqlAndOracleSql() throws Exception {
		for (String dialect : new String[] {"mysql", "oracle"}) {
			var history = Files.createTempDirectory("os-content-migration-").resolve("history.csv");
			ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
			try (Liquibase liquibase = new Liquibase("db/13.0/content-permissions.xml", resources,
				new OfflineConnection("offline:" + dialect + "?changeLogFile=" + history, resources))) {
				liquibase.setChangeLogParameter("clob.type", dialect.equals("mysql") ? "text" : "clob");
				liquibase.setChangeLogParameter("boolean.type", dialect.equals("mysql") ? "boolean" : "number(1,0)");
				ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(liquibase.getDatabase()).init();
				liquibase.validate();
				StringWriter sql = new StringWriter(); liquibase.update("", sql);
				String statements = sql.toString().toUpperCase(Locale.ROOT);
				assertTrue(statements.contains("ALTER TABLE RBAC_ROLES"));
				assertTrue(statements.contains("CONTENT_POLICIES"));
				assertTrue(statements.contains("CONTENTPOLICIESJSON_MOD"));
				assertFalse(statements.contains("UPDATE CATISSUE_"));
				assertFalse(statements.contains("DELETE FROM"));
			}
		}
	}
}
