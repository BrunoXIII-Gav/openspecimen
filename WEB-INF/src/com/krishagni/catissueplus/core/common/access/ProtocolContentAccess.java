package com.krishagni.catissueplus.core.common.access;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolRegistration;
import com.krishagni.catissueplus.core.biospecimen.domain.Participant;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.Visit;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.TransactionCache;
import com.krishagni.catissueplus.core.de.domain.DeObject;
import com.krishagni.catissueplus.core.common.util.AuthUtil;
import com.krishagni.catissueplus.core.common.util.Utility;
import com.krishagni.rbac.common.errors.RbacErrorCode;
import com.krishagni.rbac.domain.Role;
import com.krishagni.rbac.events.ProtocolContentPolicy;

import edu.common.dynamicextensions.domain.nui.Container;

/** Additional content restrictions. Never substitutes the existing object/operation checks. */
@Configurable
public class ProtocolContentAccess {

	public static final String COLLECTION = "collection";
	public static final String RECEIPT = "receipt";
	public static final Set<String> LINEAGES = Set.of("New", "Derived", "Aliquot");

	public static boolean isCreationAllowed(List<Role> roles, Long cpId, String lineage) {
		if (lineage == null || !LINEAGES.contains(lineage)) return false;
		// A lineage selection only limits Create; Read or Update must never substitute for it.
		for (Role role : roles) {
			Set<String> resources = "New".equals(lineage) ? Set.of("Specimen", "PrimarySpecimen", "ALL") : Set.of("Specimen", "ALL");
			boolean grant = role.getAcl().stream().anyMatch(acl -> resources.contains(acl.getResource().getName()) &&
				acl.getOperations().stream().anyMatch(op -> Set.of("Create", "ALL").contains(op.getOperation().getName())));
			ProtocolContentPolicy rule = policy(role, cpId);
			if (grant && (rule == null || rule.getCreateLineages() == null || rule.getCreateLineages().contains(lineage))) return true;
		}
		return false;
	}

	public boolean isCreationAllowed(Long cpId, String lineage) {
		User user = AuthUtil.getCurrentUser();
		return user == null || user.isAdmin() || isCreationAllowed(roles(cpId), cpId, lineage);
	}

	public void ensureCreationAllowed(Long cpId, String lineage) {
		if (!isCreationAllowed(cpId, lineage)) throw OpenSpecimenException.userError(RbacErrorCode.ACCESS_DENIED);
	}

	public void ensureCreationAllowed(Specimen existing, Specimen specimen) {
		if (existing == null || (!existing.isCollected() && specimen.isCollected())) {
			ensureCreationAllowed(specimen.getCollectionProtocol().getId(), specimen.getLineage());
		}
	}

	@Autowired
	private com.krishagni.rbac.repository.DaoFactory rbacDaos;

	@Autowired
	private com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory daos;

	private static ProtocolContentAccess instance;

	public static ProtocolContentAccess getInstance() {
		if (instance == null || instance.rbacDaos == null) instance = new ProtocolContentAccess();
		return instance;
	}

	public static List<ProtocolContentPolicy> policies(Role role) {
		if (role.getContentPoliciesJson() == null) return Collections.emptyList();
		return Arrays.asList(Utility.jsonToObject(role.getContentPoliciesJson(), ProtocolContentPolicy[].class));
	}

	/** Exact protocol takes precedence over this role's all-protocols default. */
	public static ProtocolContentPolicy policy(Role role, Long cpId) {
		ProtocolContentPolicy fallback = null;
		for (ProtocolContentPolicy rule : policies(role)) {
			if (rule.getCpId().equals(cpId)) return rule;
			if (rule.getCpId() == -1L) fallback = rule;
		}
		return fallback;
	}

	public static boolean isAllowed(List<Role> roles, Long cpId, String key) {
		return isAllowed(roles, cpId, key, COLLECTION.equals(key) || RECEIPT.equals(key) ?
			Set.of("Specimen", "PrimarySpecimen") : CLINICAL_RESOURCES);
	}

	private static boolean isAllowed(List<Role> roles, Long cpId, String key, Set<String> resources) {
		for (Role role : roles) {
			if (!hasClinicalAccess(role, resources)) continue;
			ProtocolContentPolicy rule = policy(role, cpId);
			if (rule == null || rule.getAllowed().contains(key)) return true;
		}
		return false;
	}

	private static final Set<String> CLINICAL_RESOURCES = Set.of("ParticipantPhi", "ParticipantDeid", "Visit", "Specimen", "PrimarySpecimen");

	private static boolean hasClinicalAccess(Role role, Set<String> resources) {
		return role.getAcl().stream().anyMatch(acl ->
			(resources.contains(acl.getResource().getName()) || "ALL".equals(acl.getResource().getName())) &&
			acl.getOperations().stream().anyMatch(op -> Set.of("Read", "Create", "Update", "ALL").contains(op.getOperation().getName())));
	}

	private static Set<String> resources(String entityType) {
		if (entityType != null && entityType.startsWith("Specimen") && !entityType.startsWith("SpecimenCollectionGroup")) return Set.of("Specimen", "PrimarySpecimen");
		if (entityType != null && (entityType.startsWith("Visit") || entityType.startsWith("SpecimenCollectionGroup"))) return Set.of("Visit");
		if (entityType != null && (entityType.startsWith("Participant") || entityType.equals("CommonParticipant"))) return Set.of("ParticipantPhi", "ParticipantDeid");
		return CLINICAL_RESOURCES;
	}

	public boolean isAllowed(Long cpId, String key, String entityType) {
		User user = AuthUtil.getCurrentUser();
		if (user == null || user.isAdmin()) return true;
		return isAllowed(roles(cpId), cpId, key, resources(entityType));
	}

	public boolean isAllowed(Long cpId, String key) {
		User user = AuthUtil.getCurrentUser();
		if (user == null || user.isAdmin()) return true; // existing authentication checks remain mandatory
		return isAllowed(roles(cpId), cpId, key);
	}

	public boolean isUnrestricted(Long cpId) {
		User user = AuthUtil.getCurrentUser();
		if (user == null || user.isAdmin()) return true;
		List<Role> roles = roles(cpId).stream().filter(role -> hasClinicalAccess(role, CLINICAL_RESOURCES)).collect(Collectors.toList());
		return !roles.isEmpty() && roles.stream().allMatch(role -> policy(role, cpId) == null);
	}

	public boolean hasClinicalContext(Long cpId) {
		return roles(cpId).stream().anyMatch(role -> hasClinicalAccess(role, CLINICAL_RESOURCES));
	}

	private List<Role> roles(Long cpId) {
		User user = AuthUtil.getCurrentUser();
		if (user == null || cpId == null || user.getInstitute() == null) return Collections.emptyList();
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			return rbacDaos.getRoleDao().getProtocolContentRoles(user.getId(), cpId, user.getInstitute().getId());
		}
		// Cache only for this transaction. Role changes take effect on the next request.
		Map<String, List<Role>> cache = TransactionCache.getInstance()
			.get("protocolContentRoles", new HashMap<>());
		return cache.computeIfAbsent(user.getId() + ":" + cpId,
			key -> rbacDaos.getRoleDao().getProtocolContentRoles(user.getId(), cpId, user.getInstitute().getId()));
	}

	public void ensureAllowed(Long cpId, String key) {
		if (!isAllowed(cpId, key)) throw OpenSpecimenException.userError(RbacErrorCode.ACCESS_DENIED);
	}

	public static String formKey(Long formId) {
		Container form = Container.getContainer(formId);
		return formKey(formId, form == null ? null : form.getName());
	}

	public static String formKey(Long formId, String name) {
		if ("SpecimenCollectionEvent".equals(name)) return COLLECTION;
		if ("SpecimenReceivedEvent".equals(name)) return RECEIPT;
		return "form:" + formId;
	}

	public List<Long> protocolIds(String entityType, Long objectId) {
		if (entityType == null || objectId == null) return Collections.emptyList();
		String type = entityType.split("-")[0];
		switch (type) {
			case "Specimen", "SpecimenEvent", "SpecimenExtension", "SpecimenPrimaryExtension", "SpecimenDerivedExtension", "SpecimenAliquotExtension": {
				Specimen specimen = daos.getSpecimenDao().getById(objectId);
				return specimen == null ? Collections.emptyList() : List.of(specimen.getCollectionProtocol().getId());
			}
			case "SpecimenCollectionGroup", "VisitExtension": {
				Visit visit = daos.getVisitsDao().getById(objectId);
				return visit == null ? Collections.emptyList() : List.of(visit.getCollectionProtocol().getId());
			}
			case "Participant", "ParticipantExtension": {
				CollectionProtocolRegistration cpr = daos.getCprDao().getById(objectId);
				return cpr == null ? Collections.emptyList() : List.of(cpr.getCollectionProtocol().getId());
			}
			case "CommonParticipant": {
				Participant participant = daos.getParticipantDao().getById(objectId);
				if (participant == null) return Collections.emptyList();
				List<Long> result = new ArrayList<>();
				for (CollectionProtocolRegistration cpr : participant.getCprs()) result.add(cpr.getCollectionProtocol().getId());
				return result;
			}
			default: return Collections.emptyList();
		}
	}

	public boolean isFormAllowed(String entityType, Long objectId, Long formId) {
		List<Long> cpIds = protocolIds(entityType, objectId);
		// Shared participant data must be permitted in every associated protocol.
		return cpIds.stream().allMatch(cpId -> isAllowed(cpId, formKey(formId), entityType));
	}

	public void ensureFormAllowed(String entityType, Long objectId, Long formId) {
		if (!isFormAllowed(entityType, objectId, formId)) throw OpenSpecimenException.userError(RbacErrorCode.ACCESS_DENIED);
	}

	public boolean isExtensionAllowed(DeObject extension) {
		if (extension == null || extension.getFormId() == null) return true;
		if (extension.isCpBased() && extension.getCpId() != null && extension.getCpId() > 0) {
			return isAllowed(extension.getCpId(), formKey(extension.getFormId()), extension.getEntityType());
		}
		return isFormAllowed(extension.getEntityType(), extension.getObjectId(), extension.getFormId());
	}

	public boolean hasRestrictions() {
		User user = AuthUtil.getCurrentUser();
		if (user == null || user.isAdmin()) return false;
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			return rbacDaos.getRoleDao().hasContentPolicies(user.getId());
		}
		Map<Long, Boolean> cache = TransactionCache.getInstance().get("protocolContentRestrictions", new HashMap<>());
		return cache.computeIfAbsent(user.getId(), id -> rbacDaos.getRoleDao().hasContentPolicies(id));
	}

	public boolean isQueryAllowed(Long cpId, String aql) {
		if (!hasRestrictions() || (cpId != null && cpId > 0 && isUnrestricted(cpId))) return true;
		return ProtocolQueryAccess.permits(aql, name -> isQueryFieldAllowed(cpId, name));
	}

	public boolean isQueryExpressionAllowed(Long cpId, String expression) {
		return isQueryAllowed(cpId, "select " + expression + " where 1 = 1");
	}

	public void ensureQueryAllowed(Long cpId, String querySpace, String aql) {
		// Query spaces may remap native field names to arbitrary SQL sources.
		if (querySpace != null && !querySpace.isBlank()) {
			ensureUnrestrictedBulkAccess(cpId);
		} else if (!isQueryAllowed(cpId, aql)) {
			throw OpenSpecimenException.userError(RbacErrorCode.CONTENT_BULK_RESTRICTED);
		}
	}

	private boolean isQueryFieldAllowed(Long cpId, String field) {
		if (!ProtocolQueryAccess.isNativeField(field)) return false;
		String key = null;
		if (field.contains(".spmnCollRecvDetails.")) {
			String leaf = field.substring(field.indexOf(".spmnCollRecvDetails.") + ".spmnCollRecvDetails.".length());
			key = leaf.startsWith("coll") ? COLLECTION : RECEIPT;
		} else if (field.contains(".specimenStatistics.collector")) {
			key = COLLECTION;
		} else if (field.contains(".specimenStatistics.receiver")) {
			key = RECEIPT;
		} else if (field.contains(".specimenStatistics.processingTime") || field.contains(".specimenStatistics.ischemiaTime")) {
			return cpId != null && cpId > 0 && isAllowed(cpId, COLLECTION) && isAllowed(cpId, RECEIPT);
		}
		return key == null || (cpId != null && cpId > 0 && isAllowed(cpId, key));
	}

	/** Bulk/query paths not capable of field redaction must fail closed for restricted users. */
	@PlusTransactional
	public void ensureUnrestrictedBulkAccess() {
		ensureUnrestrictedBulkAccess(null);
	}

	@PlusTransactional
	public void ensureUnrestrictedBulkAccess(Long cpId) {
		User user = AuthUtil.getCurrentUser();
		if (cpId != null && cpId > 0 && isUnrestricted(cpId)) return;
		if (user != null && !user.isAdmin() && rbacDaos.getRoleDao().hasContentPolicies(user.getId())) {
			throw OpenSpecimenException.userError(RbacErrorCode.CONTENT_BULK_RESTRICTED);
		}
	}
}
