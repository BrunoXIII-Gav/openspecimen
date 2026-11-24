package com.krishagni.catissueplus.core.common.service.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import org.junit.Test;

import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.common.repository.UniqueIdGenerator;

public class DefaultMpiGeneratorTest {
	@Test
	public void usesConfiguredStartNumberOnlyWhenCreatingMpiSequence() throws Exception {
		UniqueIdGenerator ids = proxy(UniqueIdGenerator.class, (method, args) -> {
			assertEquals("getUniqueId", method);
			assertArrayEquals(new Object[] {"MPI", "MPI", 499L}, args);
			return 500L;
		});
		DaoFactory daos = proxy(DaoFactory.class, (method, args) -> {
			assertEquals("getUniqueIdGenerator", method);
			return ids;
		});

		DefaultMpiGenerator generator = new DefaultMpiGenerator("MPI-%04d", 500);
		Field daoFactory = DefaultMpiGenerator.class.getDeclaredField("daoFactory");
		daoFactory.setAccessible(true);
		daoFactory.set(generator, daos);

		assertEquals("MPI-0500", generator.generateMpi());
	}

	@Test
	public void usesOneWhenConfiguredStartNumberIsNotPositive() throws Exception {
		UniqueIdGenerator ids = proxy(UniqueIdGenerator.class, (method, args) -> {
			assertEquals("getUniqueId", method);
			assertArrayEquals(new Object[] {"MPI", "MPI", 0L}, args);
			return 1L;
		});
		DaoFactory daos = proxy(DaoFactory.class, (method, args) -> ids);

		DefaultMpiGenerator generator = new DefaultMpiGenerator("MPI-%d", 0);
		Field daoFactory = DefaultMpiGenerator.class.getDeclaredField("daoFactory");
		daoFactory.setAccessible(true);
		daoFactory.set(generator, daos);

		assertEquals("MPI-1", generator.generateMpi());
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(Class<T> type, Invocation invocation) {
		return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type},
			(obj, method, args) -> invocation.invoke(method.getName(), args));
	}

	private interface Invocation {
		Object invoke(String method, Object[] args);
	}
}
