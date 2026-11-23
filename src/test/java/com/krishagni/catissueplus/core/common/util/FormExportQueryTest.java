package com.krishagni.catissueplus.core.common.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.sql.spi.NativeQueryImplementor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Element;

import com.krishagni.catissueplus.core.common.repository.Query;
import com.krishagni.catissueplus.core.de.repository.impl.FormDaoImpl;

import static org.junit.Assert.*;

/** Uses Hibernate's real parameter parser and the application's actual named SQL. No database connection is opened. */
public class FormExportQueryTest {
	private static SessionFactory factory;
	private static StandardServiceRegistry registry;
	private static final String PREFIX = "krishagni.catissueplus.beans.FormRecordEntryBean.";

	@BeforeClass
	public static void setup() throws Exception {
		registry = new StandardServiceRegistryBuilder()
			.applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
			.applySetting("hibernate.connection.provider_class", "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
			.applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
			.applySetting("hibernate.hbm2ddl.auto", "none")
			.applySetting("hibernate.cache.use_second_level_cache", false)
			.build();
		factory = new MetadataSources(registry).buildMetadata().buildSessionFactory();
		var parser = DocumentBuilderFactory.newInstance();
		parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		var mapping = parser.newDocumentBuilder().parse(Path.of("WEB-INF/src/krishagni/catissueplus/hbm/FormRecordEntryBean.hbm.xml").toFile());
		try (Session session = factory.openSession()) {
			var queries = mapping.getElementsByTagName("sql-query");
			for (int i = 0; i < queries.getLength(); ++i) {
				Element query = (Element) queries.item(i);
				if (List.of("getRegistrationRecords", "getParticipantRecords", "getVisitRecords", "getSpecimenRecords").contains(query.getAttribute("name"))) {
					factory.addNamedQuery(PREFIX + query.getAttribute("name"), session.createNativeQuery(query.getTextContent(), Object[].class));
				}
			}
		}
	}

	@AfterClass
	public static void cleanup() {
		if (factory != null) factory.close();
		if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
	}

	@Test
	public void hibernateRewritesNamedParametersButTheMementoKeepsTheOriginalSql() {
		try (Session session = factory.openSession()) {
			var query = session.createNamedQuery(PREFIX + "getRegistrationRecords", Object[].class);
			assertFalse(query.getQueryString().contains(":formId"));
			assertTrue(query.unwrap(NativeQueryImplementor.class).toMemento("test").getOriginalSqlString().contains(":formId"));
		}
	}

	@Test public void registrationFormsKeepTheFormIdAndPpidFilters() { check("registration"); }
	@Test public void commonParticipantFormsKeepTheFormIdAndPpidFilters() { check("participant"); }
	@Test public void visitFormsKeepTheFormIdAndVisitNameFilters() { check("visit"); }
	@Test public void specimenAndEventFormsKeepBothFormAndEntityTypeFilters() { check("specimen"); }

	private void check(String type) {
		try (Session session = factory.openSession()) {
			CaptureDao dao = new CaptureDao(session);
			for (List<String> names : List.of(List.<String>of(), List.of("NRC-0022.1.1", "name' OR 1=1 --"))) {
				dao.bindings.clear(); dao.start = dao.limit = null;
				List<Map<String, Object>> records;
				String nameParameter;
				if (type.equals("registration")) {
					records = dao.getRegistrationRecords(1L, null, 176L, names, 20, 100); nameParameter = "ppids";
				} else if (type.equals("participant")) {
					records = dao.getParticipantRecords(1L, null, 176L, names, 20, 100); nameParameter = "ppids";
				} else if (type.equals("visit")) {
					records = dao.getVisitRecords(1L, null, 176L, names, 20, 100); nameParameter = "visitNames";
				} else {
					records = dao.getSpecimenRecords(1L, null, 176L, "SpecimenEvent", names, 20, 100); nameParameter = "spmnLabels";
					assertEquals("SpecimenEvent", dao.bindings.get("entityType"));
				}
				assertTrue(records.isEmpty());
				assertEquals(176L, dao.bindings.get("formId"));
				assertTrue(dao.sql.contains(":formId")); assertTrue(dao.sql.contains("cp.identifier = 1"));
				if (names.isEmpty()) {
					assertEquals(Integer.valueOf(20), dao.start); assertEquals(Integer.valueOf(100), dao.limit);
				} else {
					assertEquals(names, dao.bindings.get(nameParameter));
					assertFalse(dao.sql.contains(names.get(1))); assertTrue(dao.sql.contains(":" + nameParameter));
					assertNull(dao.start); assertNull(dao.limit);
				}
			}
		}
	}

	private static class CaptureDao extends FormDaoImpl {
		final Session session; final Map<String, Object> bindings = new HashMap<>();
		String sql; Integer start, limit;
		CaptureDao(Session session) { this.session = session; }
		@Override protected Session getCurrentSession() { return session; }
		@Override protected <R> Query<R> createNativeQuery(String text, Class<R> returnType) {
			sql = text;
			NativeQuery<?> real = session.createNativeQuery(text, Object[].class);
			NativeQuery<?> captured = (NativeQuery<?>) Proxy.newProxyInstance(NativeQuery.class.getClassLoader(), new Class<?>[]{NativeQuery.class}, (proxy, method, args) -> {
				if (method.getName().equals("list")) return List.of();
				if (method.getName().equals("setParameter") || method.getName().equals("setParameterList")) bindings.put((String) args[0], args[1]);
				if (method.getName().equals("setFirstResult")) start = (Integer) args[0];
				if (method.getName().equals("setMaxResults")) limit = (Integer) args[0];
				try { Object result = method.invoke(real, args); return result == real ? proxy : result; }
				catch (InvocationTargetException e) { throw e.getCause(); }
			});
			Session proxy = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class}, (p, method, args) -> {
				if (method.getName().equals("createNativeQuery")) return captured;
				throw new AssertionError(method.getName());
			});
			return Query.createNativeQuery(proxy, text, returnType);
		}
	}
}
