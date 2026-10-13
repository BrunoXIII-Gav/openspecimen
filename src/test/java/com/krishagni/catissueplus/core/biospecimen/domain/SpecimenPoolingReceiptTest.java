package com.krishagni.catissueplus.core.biospecimen.domain;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Date;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.krishagni.catissueplus.core.administrative.domain.PermissibleValue;
import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.administrative.repository.PermissibleValueDao;
import com.krishagni.catissueplus.core.administrative.repository.UserDao;
import com.krishagni.catissueplus.core.biospecimen.events.ReceivedEventDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.repository.SpecimenDao;
import com.krishagni.catissueplus.core.common.util.AuthUtil;

import static org.junit.Assert.*;

public class SpecimenPoolingReceiptTest {

	private User operator;
	private CollectionProtocol cp;
	private DaoFactory daos;
	private int pooledEventsSaved;
	private Specimen poolingSpecimen;

	@Before
	public void setUp() {
		operator = new User();
		operator.setId(1L);
		operator.setType(User.Type.NONE);
		AuthUtil.setCurrentUser(operator);
		cp = new CollectionProtocol();
		cp.setId(1L);
		pooledEventsSaved = 0;
		poolingSpecimen = null;

		PermissibleValueDao pvDao = proxy(PermissibleValueDao.class, (method, args) -> {
			assertPoolEventPersistedBeforeQuery();
			PermissibleValue pv = new PermissibleValue();
			pv.setValue((String) args[1]);
			return pv;
		});
		UserDao users = proxy(UserDao.class, (method, args) -> {
			assertPoolEventPersistedBeforeQuery();
			return operator;
		});
		SpecimenDao specimens = proxy(SpecimenDao.class, (method, args) -> {
			assertEquals("savedPooledEvent", method);
			pooledEventsSaved++;
			((SpecimenPooledEvent) args[0]).setId(1L);
			return null;
		});
		daos = proxy(DaoFactory.class, (method, args) -> {
			switch (method) {
				case "getPermissibleValueDao": return pvDao;
				case "getUserDao": return users;
				case "getSpecimenDao": return specimens;
				default: throw new AssertionError("Unexpected DAO: " + method);
			}
		});
	}

	@After
	public void clearUser() {
		AuthUtil.clearCurrentUser();
	}

	@Test
	public void poolEventIsPersistedBeforeReceiptQueriesCanFlushTheSession() throws Exception {
		poolingSpecimen = specimen(100L);
		poolingSpecimen.addPoolSpecimen(specimen(1L), false);

		assertEquals(1, pooledEventsSaved);
		assertEquals(Specimen.ACCEPTABLE, poolingSpecimen.getReceivedQuality().getValue());
		assertEquals(poolingSpecimen.getPooledEvent().getTime(), poolingSpecimen.getReceivedTime());
	}

	@Test
	public void poolIsReceivedAtTheExactPoolingTimeByThePoolingOperator() throws Exception {
		Specimen pool = specimen(100L);
		Date collectionTime = new Date(1000L);
		pool.setCollectionTime(collectionTime);
		pool.addPoolSpecimen(specimen(1L), false);

		assertEquals(Specimen.ACCEPTABLE, pool.getReceivedQuality().getValue());
		assertEquals(pool.getPooledEvent().getTime(), pool.getReceivedTime());
		assertSame(operator, pool.getReceivedUser());
		assertEquals(collectionTime, pool.getCollectionTime());
		assertEquals(pool.getInitialQuantity(), pool.getReceivedQuantity());
		assertEquals(new BigDecimal("4"), pool.getAvailableQuantity());
	}

	@Test
	public void addingMoreSourcesKeepsOneReceiptAndDoesNotReceiveTheSources() throws Exception {
		Specimen pool = specimen(100L);
		Specimen first = specimen(1L);
		Specimen second = specimen(2L);
		pool.addPoolSpecimen(first, false);
		Date firstReceipt = pool.getReceivedTime();
		pool.addPoolSpecimen(second, false);

		assertSame(firstReceipt, pool.getReceivedTime());
		assertEquals(1, pooledEventsSaved);
		assertEquals(2, pool.getPooledEvent().getPoolItems().size());
		assertEquals(Specimen.TO_BE_RECEIVED, first.getReceivedQuality().getValue());
		assertEquals(Specimen.TO_BE_RECEIVED, second.getReceivedQuality().getValue());
		assertNull(first.getReceivedTime());
		assertNull(second.getReceivedTime());
	}

	@Test
	public void mixedConsumeAndKeepChoicesStillCreateOneReceivedPool() throws Exception {
		poolingSpecimen = specimen(100L);
		Specimen consumed = specimen(1L);
		Specimen kept = specimen(2L);
		poolingSpecimen.addPoolSpecimen(consumed, true);
		poolingSpecimen.addPoolSpecimen(kept, false);

		assertTrue(consumed.isClosed());
		assertFalse(kept.isClosed());
		assertEquals(2, poolingSpecimen.getPooledEvent().getPoolItems().size());
		assertEquals(1, pooledEventsSaved);
		assertEquals(Specimen.ACCEPTABLE, poolingSpecimen.getReceivedQuality().getValue());
		assertEquals(poolingSpecimen.getPooledEvent().getTime(), poolingSpecimen.getReceivedTime());
	}

	@Test
	public void ordinaryCollectionCanStillRemainToBeReceived() throws Exception {
		Specimen specimen = specimen(1L);
		ReceivedEventDetail receipt = new ReceivedEventDetail();
		receipt.setReceivedQuality(Specimen.TO_BE_RECEIVED);
		specimen.setReceivedDetails(null, receipt);

		assertEquals(Specimen.TO_BE_RECEIVED, specimen.getReceivedQuality().getValue());
		assertNull(specimen.getReceivedTime());
		assertNull(specimen.getReceivedUser());
		assertNull(specimen.getPooledEvent());
		assertEquals(0, pooledEventsSaved);
	}

	private Specimen specimen(Long id) throws Exception {
		Specimen specimen = new Specimen() {
			@Override public boolean isEditAllowed() { return true; }
			@Override public boolean isStorageSiteBasedAccessRightsEnabled() { return false; }
			@Override public void close(User user, Date time, String reason, String comments) {
				setActivityStatus("Closed");
			}
		};
		specimen.setId(id);
		specimen.setLineage(Specimen.NEW);
		specimen.setCollectionStatus(Specimen.COLLECTED);
		specimen.setCollectionProtocol(cp);
		specimen.setInitialQuantity(new BigDecimal("4"));
		specimen.setAvailableQuantity(new BigDecimal("4"));
		PermissibleValue quality = new PermissibleValue();
		quality.setValue(Specimen.TO_BE_RECEIVED);
		specimen.setReceivedQuality(quality);
		Field field = Specimen.class.getDeclaredField("daoFactory");
		field.setAccessible(true);
		field.set(specimen, daos);
		return specimen;
	}

	private interface Invocation {
		Object invoke(String method, Object[] args);
	}

	private void assertPoolEventPersistedBeforeQuery() {
		if (poolingSpecimen != null && poolingSpecimen.getPooledEvent() != null) {
			// Hibernate may flush before a DAO query. A managed specimen cannot
			// reference an event that is still transient at that point.
			assertNotNull("Receipt query would flush a transient pooled event",
				poolingSpecimen.getPooledEvent().getId());
		}
	}

	private <T> T proxy(Class<T> type, Invocation handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
			(proxy, method, args) -> handler.invoke(method.getName(), args)));
	}
}
