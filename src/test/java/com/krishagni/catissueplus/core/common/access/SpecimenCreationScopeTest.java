package com.krishagni.catissueplus.core.common.access;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import com.krishagni.rbac.domain.Role;
import com.krishagni.rbac.events.ProtocolContentPolicy;
import static org.junit.Assert.*;

public class SpecimenCreationScopeTest {
	@Test public void oldRulesKeepAllLineagesAndOnlyPrimarySelectionRestrictsChildren() {
		Role legacy = creator("Specimen", ProtocolContentAccessTest.rule(1L, "collection"));
		for (String lineage : ProtocolContentAccess.LINEAGES) assertTrue(allowed(List.of(legacy), 1L, lineage));
		Role limited = creator("Specimen", scope(1L, "New"));
		assertTrue(allowed(List.of(limited), 1L, "New"));
		assertFalse(allowed(List.of(limited), 1L, "Derived"));
		assertFalse(allowed(List.of(limited), 1L, "Aliquot"));
		assertTrue(allowed(List.of(limited), 2L, "Derived"));
	}

	@Test public void derivativesAndAliquotsCanBeSelectedIndependentlyAndEmptyBlocksAll() {
		Role derived = creator("Specimen", scope(1L, "Derived"));
		assertFalse(allowed(List.of(derived), 1L, "New")); assertTrue(allowed(List.of(derived), 1L, "Derived"));
		assertFalse(allowed(List.of(derived), 1L, "Aliquot"));
		for (String lineage : ProtocolContentAccess.LINEAGES) assertFalse(allowed(List.of(creator("Specimen", scope(1L))), 1L, lineage));
	}

	@Test public void scopeIsNotACreationGrantAndUnrelatedOrReadOnlyRolesCannotCancelIt() {
		Role limited = creator("Specimen", scope(1L, "New"));
		assertFalse(allowed(List.of(limited, creator("Visit"), ProtocolContentAccessTest.role("Specimen")), 1L, "Derived"));
		assertFalse(allowed(List.of(limited, creator("PrimarySpecimen")), 1L, "Aliquot"));
		assertTrue(allowed(List.of(limited, creator("Specimen")), 1L, "Derived"));
		assertTrue(allowed(List.of(limited, creator("Specimen", scope(1L, "Aliquot"))), 1L, "Aliquot"));
	}

	@Test public void createMustBeGrantedEvenWithoutAScopeAndStoredScopeCannotSubstituteForCreate() {
		Role readOnly = ProtocolContentAccessTest.role("Specimen");
		Role updateOnly = ProtocolContentAccessTest.role("Specimen", scope(1L, "New", "Derived", "Aliquot"));
		updateOnly.getAcl().forEach(acl -> acl.getOperations().forEach(op -> op.getOperation().setName("Update")));
		for (String lineage : ProtocolContentAccess.LINEAGES) {
			assertFalse(allowed(List.of(readOnly), 1L, lineage));
			assertFalse(allowed(List.of(updateOnly), 1L, lineage));
			assertFalse(allowed(List.of(creator("Visit")), 1L, lineage));
		}
		assertTrue(allowed(List.of(creator("PrimarySpecimen")), 1L, "New"));
		assertFalse(allowed(List.of(creator("PrimarySpecimen")), 1L, "Derived"));
		assertFalse(allowed(List.of(creator("PrimarySpecimen")), 1L, "Aliquot"));
	}

	@Test public void exactProtocolOverridesDefaultScope() {
		Role role = creator("Specimen", scope(-1L, "New"), scope(2L, "Aliquot"));
		assertTrue(allowed(List.of(role), 1L, "New")); assertFalse(allowed(List.of(role), 1L, "Aliquot"));
		assertFalse(allowed(List.of(role), 2L, "New")); assertTrue(allowed(List.of(role), 2L, "Aliquot"));
	}

	@Test public void olderClientsDoNotEraseCreationScopeAndExplicitAllRestoresIt() {
		Role saved = creator("Specimen", scope(1L, "New"));
		saved.updateRole(creator("Specimen", ProtocolContentAccessTest.rule(1L, "collection")));
		assertEquals(Set.of("New"), ProtocolContentAccess.policies(saved).get(0).getCreateLineages());
		saved.updateRole(creator("Specimen", scope(1L, "New", "Derived", "Aliquot")));
		assertTrue(allowed(List.of(saved), 1L, "Aliquot"));
	}

	static ProtocolContentPolicy scope(Long cpId, String... lineages) {
		ProtocolContentPolicy rule = ProtocolContentAccessTest.rule(cpId, "collection");
		rule.setCreateLineages(Set.of(lineages)); return rule;
	}

	static Role creator(String resource, ProtocolContentPolicy... policies) {
		Role role = ProtocolContentAccessTest.role(resource, policies);
		role.getAcl().forEach(acl -> acl.getOperations().forEach(op -> op.getOperation().setName("Create")));
		return role;
	}

	private boolean allowed(List<Role> roles, Long cpId, String lineage) {
		return ProtocolContentAccess.isCreationAllowed(roles, cpId, lineage);
	}
}
