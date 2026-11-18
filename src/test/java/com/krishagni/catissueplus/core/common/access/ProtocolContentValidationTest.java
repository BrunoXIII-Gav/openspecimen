package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.Test;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.rbac.domain.Role;
import com.krishagni.rbac.events.ProtocolContentPolicy;
import com.krishagni.rbac.events.RoleDetail;
import com.krishagni.rbac.service.impl.RbacServiceImpl;

import static org.junit.Assert.*;

public class ProtocolContentValidationTest {

	@Test
	public void acceptsExplicitEmptySelectionAndUnrestrictedReset() throws Exception {
		assertEquals(1, ProtocolContentAccess.policies(createRole(List.of(ProtocolContentAccessTest.rule(1L)))).size());
		assertEquals("[]", createRole(List.of()).getContentPoliciesJson());
		assertNull(createRole(null).getContentPoliciesJson());
	}

	@Test
	public void rejectsDuplicateProtocolsInvalidScopesAndInvalidKeys() {
		assertThrows(OpenSpecimenException.class, () -> createRole(List.of(ProtocolContentAccessTest.rule(1L), ProtocolContentAccessTest.rule(1L))));
		assertThrows(OpenSpecimenException.class, () -> createRole(List.of(ProtocolContentAccessTest.rule(0L))));
		assertThrows(OpenSpecimenException.class, () -> createRole(List.of(ProtocolContentAccessTest.rule(-2L))));
		assertThrows(OpenSpecimenException.class, () -> createRole(List.of(ProtocolContentAccessTest.rule(1L, "form:0"))));
		assertThrows(OpenSpecimenException.class, () -> createRole(List.of(ProtocolContentAccessTest.rule(1L, "unknown-event"))));
	}

	private Role createRole(List<ProtocolContentPolicy> policies) throws Exception {
		RoleDetail detail = new RoleDetail(); detail.setName("Collector"); detail.setContentPolicies(policies);
		var method = RbacServiceImpl.class.getDeclaredMethod("createRole", RoleDetail.class); method.setAccessible(true);
		try { return (Role) method.invoke(new RbacServiceImpl(), detail); }
		catch (InvocationTargetException exception) { throw (RuntimeException) exception.getCause(); }
	}

	@Test public void validatesCreationScopeAndKeepsExplicitEmptyScope() throws Exception {
		var rule = SpecimenCreationScopeTest.scope(1L);
		assertEquals(java.util.Set.of(), ProtocolContentAccess.policies(createRole(List.of(rule))).get(0).getCreateLineages());
		rule.setCreateLineages(java.util.Set.of("PRIMARY"));
		assertThrows(OpenSpecimenException.class, () -> createRole(List.of(rule)));
	}
}
