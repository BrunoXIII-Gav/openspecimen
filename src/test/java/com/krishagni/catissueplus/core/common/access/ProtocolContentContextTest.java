package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolRegistration;
import com.krishagni.catissueplus.core.biospecimen.domain.Participant;
import com.krishagni.catissueplus.core.biospecimen.repository.CollectionProtocolRegistrationDao;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.repository.ParticipantDao;

import static org.junit.Assert.*;

public class ProtocolContentContextTest {

	@Test
	public void participantExtensionBelongsToTheRegistrationButCommonFormsBelongToTheParticipant() throws Exception {
		CollectionProtocol first = new CollectionProtocol(); first.setId(1L);
		CollectionProtocol second = new CollectionProtocol(); second.setId(2L);
		CollectionProtocolRegistration cpr = new CollectionProtocolRegistration(); cpr.setId(10L); cpr.setCollectionProtocol(first);
		CollectionProtocolRegistration other = new CollectionProtocolRegistration(); other.setId(11L); other.setCollectionProtocol(second);
		Participant participant = new Participant(); participant.setId(20L); participant.setCprs(Set.of(cpr, other));
		cpr.setParticipant(participant); other.setParticipant(participant);
		CollectionProtocolRegistrationDao registrations = proxy(CollectionProtocolRegistrationDao.class, (method, args) -> {
			assertEquals(10L, args[0]); return cpr;
		});
		ParticipantDao participants = proxy(ParticipantDao.class, (method, args) -> {
			assertEquals(20L, args[0]); return participant;
		});
		DaoFactory daos = proxy(DaoFactory.class, (method, args) -> {
			if (method.equals("getCprDao")) return registrations;
			if (method.equals("getParticipantDao")) return participants;
			throw new AssertionError(method);
		});
		ProtocolContentAccess access = new ProtocolContentAccess();
		var field = ProtocolContentAccess.class.getDeclaredField("daos"); field.setAccessible(true); field.set(access, daos);
		assertEquals(List.of(1L), access.protocolIds("ParticipantExtension", 10L));
		assertEquals(List.of(1L), access.protocolIds("Participant", 10L));
		assertEquals(Set.of(1L, 2L), Set.copyOf(access.protocolIds("CommonParticipant", 20L)));
	}

	private interface Invocation { Object call(String method, Object[] args); }

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Invocation invocation) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> invocation.call(method.getName(), args));
	}
}
