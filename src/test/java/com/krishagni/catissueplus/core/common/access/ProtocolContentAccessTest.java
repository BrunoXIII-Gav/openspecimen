package com.krishagni.catissueplus.core.common.access;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.krishagni.catissueplus.core.common.util.Utility;
import com.krishagni.rbac.domain.Operation;
import com.krishagni.rbac.domain.Resource;
import com.krishagni.rbac.domain.ResourceInstanceOp;
import com.krishagni.rbac.domain.Role;
import com.krishagni.rbac.domain.RoleAccessControl;
import com.krishagni.rbac.events.ProtocolContentPolicy;
import com.krishagni.rbac.events.RoleDetail;

import static org.junit.Assert.*;

public class ProtocolContentAccessTest {

	@Test
	public void legacyRolesRetainAccess() {
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role("Specimen")), 1L, "receipt"));
	}

	@Test
	public void limitedRolesAllowOnlySelectedContent() {
		Role role = role("Specimen", rule(1L, "collection", "form:12"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role), 1L, "collection"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role), 1L, "form:12"));
		assertFalse(ProtocolContentAccess.isAllowed(List.of(role), 1L, "receipt"));
		assertFalse(ProtocolContentAccess.isAllowed(List.of(role), 1L, "form:13"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role), 2L, "receipt"));
	}

	@Test
	public void emptySelectionIsDifferentFromNoRestriction() {
		assertFalse(ProtocolContentAccess.isAllowed(List.of(role("Specimen", rule(1L))), 1L, "collection"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role("Specimen")), 1L, "collection"));
	}

	@Test
	public void exactProtocolOverridesRoleDefault() {
		Role role = role("Specimen", rule(-1L, "collection"), rule(2L, "receipt"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role), 1L, "collection"));
		assertFalse(ProtocolContentAccess.isAllowed(List.of(role), 1L, "receipt"));
		assertFalse(ProtocolContentAccess.isAllowed(List.of(role), 2L, "collection"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(role), 2L, "receipt"));
	}

	@Test
	public void relevantRoleGrantsAreCombinedButUnrelatedRolesCannotCancelRestrictions() {
		Role collector = role("Specimen", rule(1L, "collection"));
		Role receiver = role("Specimen", rule(1L, "receipt"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(collector, receiver), 1L, "receipt"));
		assertFalse(ProtocolContentAccess.isAllowed(List.of(collector, role("User"), role("CollectionProtocol"), role("Visit")), 1L, "receipt"));
		assertTrue(ProtocolContentAccess.isAllowed(List.of(collector, role("Specimen")), 1L, "receipt"));
	}

	@Test
	public void oldClientsCannotEraseSavedRestrictions() {
		Role existing = role("Specimen", rule(1L, "collection"));
		existing.updateRole(role("Specimen"));
		assertFalse(ProtocolContentAccess.isAllowed(List.of(existing), 1L, "receipt"));
		Role explicitReset = role("Specimen");
		explicitReset.setContentPoliciesJson("[]");
		existing.updateRole(explicitReset);
		assertTrue(ProtocolContentAccess.isAllowed(List.of(existing), 1L, "receipt"));
	}

	@Test
	public void configurationRoundTripsWithoutUsingCaptionsAsIdentifiers() {
		Role role = role("Specimen", rule(1L, "collection", "form:12"));
		assertEquals(Set.of("collection", "form:12"), RoleDetail.from(role).getContentPolicies().get(0).getAllowed());
	}

	static ProtocolContentPolicy rule(Long cpId, String... allowed) {
		ProtocolContentPolicy result = new ProtocolContentPolicy();
		result.setCpId(cpId);
		result.setAllowed(Set.of(allowed));
		return result;
	}

	static Role role(String resource, ProtocolContentPolicy... policies) {
		Resource res = new Resource(); res.setName(resource); res.setId((long) resource.hashCode());
		Operation op = new Operation(); op.setName("Read"); op.setId(1L);
		RoleAccessControl acl = new RoleAccessControl(); acl.setResource(res);
		ResourceInstanceOp permission = new ResourceInstanceOp(); permission.setOperation(op);
		acl.setOperations(new java.util.HashSet<>(Set.of(permission)));
		Role role = new Role(); role.setAcl(new java.util.HashSet<>(Set.of(acl)));
		if (policies.length > 0) role.setContentPoliciesJson(Utility.objectToJson(policies));
		return role;
	}
}
