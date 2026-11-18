package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.junit.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.krishagni.rbac.domain.Role;
import com.krishagni.rbac.repository.impl.RoleDaoImpl;

import static org.junit.Assert.*;

/** Builds the production mappings and parses the new queries without connecting to a database. */
public class ProtocolContentMappingTest {

	@Test
	public void rolePolicyMappingAndScopedQueriesAreValid() throws Exception {
		checkMappings("org.hibernate.dialect.MySQLDialect");
		checkMappings("org.hibernate.dialect.OracleDialect");
	}

	private void checkMappings(String dialect) throws Exception {
		Configuration configuration = new Configuration();
		configuration.setProperty("hibernate.dialect", dialect);
		configuration.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
		configuration.setProperty("hibernate.hbm2ddl.auto", "none");
		configuration.setProperty("hibernate.query.startup_check", "false");
		configuration.setProperty("hibernate.integration.envers.enabled", "true");
		configuration.setProperty("org.hibernate.envers.track_entities_changed_in_revision", "true");
		configuration.setProperty("org.hibernate.envers.global_with_modified_flag", "true");
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		configuration.addResource("com/krishagni/catissueplus/core/repository/hbm/filters.hbm.xml");
		for (String pattern : List.of("classpath*:com/**/*.hbm.xml", "classpath*:krishagni/catissueplus/**/*.hbm.xml")) {
			for (Resource resource : resolver.getResources(pattern)) {
				if (!"filters.hbm.xml".equals(resource.getFilename())) {
					try (var input = resource.getInputStream()) { configuration.addInputStream(input); }
				}
			}
		}

		try (SessionFactory factory = configuration.buildSessionFactory(); Session session = factory.openSession()) {
			assertNotNull(factory.getMetamodel().entity(Role.class).getAttribute("contentPoliciesJson"));
			Session parserSession = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class}, (proxy, method, args) -> {
				if (!method.getName().equals("createQuery")) return invoke(method, session, args);
				Query<?> parsed = (Query<?>) invoke(method, session, args);
				return Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[] {Query.class}, (queryProxy, queryMethod, queryArgs) -> {
					if (queryMethod.getName().equals("list")) return List.of();
					return invoke(queryMethod, parsed, queryArgs);
				});
			});
			SessionFactory parserFactory = (SessionFactory) Proxy.newProxyInstance(SessionFactory.class.getClassLoader(), new Class<?>[] {SessionFactory.class}, (proxy, method, args) -> {
				if (method.getName().equals("getCurrentSession")) return parserSession;
				throw new AssertionError(method.getName());
			});
			RoleDaoImpl dao = new RoleDaoImpl(); dao.setSessionFactory(parserFactory);
			assertTrue(dao.getProtocolContentRoles(5L, 1L, 1L).isEmpty());
			assertFalse(dao.hasContentPolicies(5L));
		}
	}

	private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
		try { return method.invoke(target, args); }
		catch (InvocationTargetException exception) { throw exception.getCause(); }
	}
}
