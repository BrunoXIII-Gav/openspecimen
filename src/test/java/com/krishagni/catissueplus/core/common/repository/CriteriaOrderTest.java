package com.krishagni.catissueplus.core.common.repository;

import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.criteria.CriteriaQuery;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CriteriaOrderTest {

	private static SessionFactory sessionFactory;

	@BeforeClass
	public static void createSessionFactory() {
		sessionFactory = new Configuration()
			.addAnnotatedClass(AuditRecord.class)
			.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
			.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
			.buildSessionFactory();
	}

	@AfterClass
	public static void closeSessionFactory() {
		if (sessionFactory != null) {
			sessionFactory.close();
		}
	}

	@Test
	public void appliesDescendingOrderUsedByAuditExportCursor() {
		try (Session session = sessionFactory.openSession()) {
			Criteria<AuditRecord> criteria = Criteria.create(session, AuditRecord.class, "re");
			criteria.addOrder(criteria.desc("re.id"));

			List<jakarta.persistence.criteria.Order> orders = orders(criteria);
			assertEquals(1, orders.size());
			assertFalse(orders.get(0).isAscending());
			assertEquals(criteria.getExpression("re.id"), orders.get(0).getExpression());
		}
	}

	@Test
	public void appendsOrderWithoutMutatingHibernateImmutableList() {
		try (Session session = sessionFactory.openSession()) {
			Criteria<AuditRecord> criteria = Criteria.create(session, AuditRecord.class, "re");
			criteria.addOrder(criteria.desc("re.revision"));
			criteria.addOrder(criteria.asc("re.id"));

			List<jakarta.persistence.criteria.Order> orders = orders(criteria);
			assertEquals(2, orders.size());
			assertFalse(orders.get(0).isAscending());
			assertTrue(orders.get(1).isAscending());
		}
	}

	@Test
	public void preservesExistingOrderByWhenAppending() {
		try (Session session = sessionFactory.openSession()) {
			Criteria<AuditRecord> criteria = Criteria.create(session, AuditRecord.class, "re");
			criteria.orderBy(criteria.asc("re.revision"));
			criteria.addOrder(criteria.desc("re.id"));

			List<jakarta.persistence.criteria.Order> orders = orders(criteria);
			assertEquals(2, orders.size());
			assertTrue(orders.get(0).isAscending());
			assertFalse(orders.get(1).isAscending());
		}
	}

	private List<jakarta.persistence.criteria.Order> orders(Criteria<AuditRecord> criteria) {
		return ((CriteriaQuery<AuditRecord>) criteria.query).getOrderList();
	}

	@Entity(name = "AuditOrderTestRecord")
	public static class AuditRecord {
		@Id
		private Long id;

		private Long revision;
	}
}
