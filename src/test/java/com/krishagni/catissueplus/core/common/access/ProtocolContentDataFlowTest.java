package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.krishagni.catissueplus.core.administrative.domain.Institute;
import com.krishagni.catissueplus.core.administrative.domain.PermissibleValue;
import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.biospecimen.domain.BaseExtensionEntity;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.events.ReceivedEventDetail;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.util.AuthUtil;
import com.krishagni.catissueplus.core.de.domain.DeObject;
import com.krishagni.catissueplus.core.de.events.ExtensionDetail;
import com.krishagni.catissueplus.core.de.events.FormSummary;
import com.krishagni.catissueplus.core.de.events.GetEntityFormRecordsOp;
import com.krishagni.catissueplus.core.de.repository.FormDao;
import com.krishagni.catissueplus.core.de.services.impl.FormServiceImpl;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.rbac.repository.DaoFactory;
import com.krishagni.rbac.repository.RoleDao;

import static org.junit.Assert.*;

public class ProtocolContentDataFlowTest {

	private ProtocolContentAccess original;
	private CollectionProtocol cp;
	private List<com.krishagni.rbac.domain.Role> effectiveRoles;

	@Before
	public void setup() throws Exception {
		Field singleton = ProtocolContentAccess.class.getDeclaredField("instance");
		singleton.setAccessible(true);
		original = (ProtocolContentAccess) singleton.get(null);
		effectiveRoles = List.of(SpecimenCreationScopeTest.creator("Specimen", ProtocolContentAccessTest.rule(1L, "collection")));
		RoleDao roles = proxy(RoleDao.class, (method, args) -> {
			if (method.equals("getProtocolContentRoles")) return effectiveRoles;
			if (method.equals("hasContentPolicies")) return true;
			throw new AssertionError(method);
		});
		DaoFactory rbac = proxy(DaoFactory.class, (method, args) -> roles);
		ProtocolContentAccess access = new ProtocolContentAccess();
		setField(access, "rbacDaos", rbac);
		singleton.set(null, access);
		Institute institute = new Institute(); institute.setId(1L);
		User user = new User(); user.setId(5L); user.setType(User.Type.NONE); user.setInstitute(institute);
		AuthUtil.setCurrentUser(user);
		cp = new CollectionProtocol(); cp.setId(1L);
	}

	@After
	public void cleanup() throws Exception {
		AuthUtil.clearCurrentUser();
		Field singleton = ProtocolContentAccess.class.getDeclaredField("instance");
		singleton.setAccessible(true); singleton.set(null, original);
	}

	@Test
	public void savingCollectionPreservesEveryExistingReceiptValue() {
		Specimen existing = specimen();
		PermissibleValue quality = new PermissibleValue(); quality.setValue("Acceptable");
		existing.setReceivedQuality(quality);
		existing.setReceivedUser(AuthUtil.getCurrentUser());
		existing.setReceivedTime(new Date(1000));
		existing.setReceivedComments("private receipt notes");
		existing.setReceivedQuantity(new BigDecimal("3"));
		existing.setReceivedQuantityReason("private reason");
		Specimen updated = specimen();
		updated.setReceivedDetails(existing, null);
		assertSame(quality, updated.getReceivedQuality());
		assertSame(existing.getReceivedUser(), updated.getReceivedUser());
		assertEquals(existing.getReceivedTime(), updated.getReceivedTime());
		assertEquals(existing.getReceivedComments(), updated.getReceivedComments());
		assertEquals(existing.getReceivedQuantity(), updated.getReceivedQuantity());
		assertEquals(existing.getReceivedQuantityReason(), updated.getReceivedQuantityReason());
		assertNull(ReceivedEventDetail.from(updated));
	}

	@Test(expected = OpenSpecimenException.class)
	public void aDirectReceiptPayloadIsRejectedEvenWhenEmpty() {
		specimen().setReceivedDetails(specimen(), new ReceivedEventDetail());
	}

	@Test(expected = OpenSpecimenException.class)
	public void receiptOnlyRoleCannotCollectAPrimaryByOmittingTheCollectionEvent() {
		effectiveRoles = List.of(ProtocolContentAccessTest.role("Specimen", ProtocolContentAccessTest.rule(1L, "receipt")));
		specimen().setCollectionDetails(null, null);
	}

	@Test
	public void savingReceiptPreservesAnExistingHiddenCollection() {
		effectiveRoles = List.of(ProtocolContentAccessTest.role("Specimen", ProtocolContentAccessTest.rule(1L, "receipt")));
		Specimen existing = specimen(); existing.setCollectionTime(new Date(42)); existing.setCollectionComments("private collection notes");
		Specimen updated = specimen(); updated.setCollectionDetails(existing, null);
		assertEquals(existing.getCollectionTime(), updated.getCollectionTime());
		assertEquals(existing.getCollectionComments(), updated.getCollectionComments());
		assertNull(com.krishagni.catissueplus.core.biospecimen.events.CollectionEventDetail.from(updated));
	}

	@Test
	public void unrelatedClinicalRoleCannotGrantAccessToASpecimenForm() {
		effectiveRoles = List.of(ProtocolContentAccessTest.role("Specimen", ProtocolContentAccessTest.rule(1L, "collection")), ProtocolContentAccessTest.role("Visit"));
		assertFalse(ProtocolContentAccess.getInstance().isAllowed(1L, "form:12", "SpecimenEvent"));
		assertTrue(ProtocolContentAccess.getInstance().isAllowed(1L, "form:12", "SpecimenCollectionGroup"));
	}

	@Test
	public void systemAdministratorsKeepAccessAndScopedQueriesInOtherProtocolsAreUnaffected() {
		ProtocolContentAccess.getInstance().ensureUnrestrictedBulkAccess(2L);
		AuthUtil.getCurrentUser().setType(User.Type.SUPER);
		assertTrue(ProtocolContentAccess.getInstance().isAllowed(1L, "receipt"));
		ProtocolContentAccess.getInstance().ensureUnrestrictedBulkAccess();
	}

	@Test
	public void pedroCanQueryProtocolNavigationWhileReceiptAndExternalFormsRemainBlocked() {
		// Same allowlist as Coordinator assigned to Pedro in CCIN, without receipt or form 126.
		effectiveRoles = List.of(ProtocolContentAccessTest.role("Specimen", ProtocolContentAccessTest.rule(1L,
			"collection", "form:19", "form:5", "form:4", "form:3", "form:26", "form:27", "form:13", "form:14",
			"form:23", "form:21", "form:10", "form:176", "form:153", "form:8", "form:7")));
		ProtocolContentAccess access = ProtocolContentAccess.getInstance();
		String criteria = "CollectionProtocol.id exists and (CollectionProtocol.__userLabels.userId not exists or CollectionProtocol.__userLabels.userId = 5) and CollectionProtocol.id = 1";
		assertTrue(access.isQueryAllowed(-1L, "select distinct CollectionProtocol.__userLabels.userId, CollectionProtocol.shortTitle, CollectionProtocol.id, CollectionProtocol.catalogId, CollectionProtocol.principalInvestigator.investigatorName, CollectionProtocol.startDate where " + criteria + " order by CollectionProtocol.__userLabels.userId desc, CollectionProtocol.shortTitle asc limit 0, 100"));
		assertTrue(access.isQueryAllowed(-1L, "select count(distinct CollectionProtocol.id) where " + criteria));
		assertTrue(access.isQueryAllowed(1L, "select Participant.id, Participant.ppid where Participant.id exists"));
		assertTrue(access.isQueryAllowed(1L, "select Specimen.spmnCollRecvDetails.collTime where Specimen.id exists"));
		assertFalse(access.isQueryAllowed(1L, "select Specimen.spmnCollRecvDetails.recvTime where Specimen.id exists"));
		assertFalse(access.isQueryAllowed(1L, "select Specimen.id where Specimen.spmnCollRecvDetails.recvQuality = \"Acceptable\""));
		assertFalse(access.isAllowed(1L, "form:126"));
		assertFalse(access.isQueryAllowed(1L, "select Participant.extensions.Hidden.requiredField where Participant.id exists"));
		assertThrows(OpenSpecimenException.class, () -> access.ensureQueryAllowed(1L, "custom-space", "select Specimen.id where Specimen.id exists"));
		assertThrows(OpenSpecimenException.class, () -> access.ensureUnrestrictedBulkAccess());
	}

	@Test
	public void allSitesAssignmentDoesNotGrantHiddenEventsOrFormsThroughTheQueryGuard() {
		String sites = com.krishagni.catissueplus.core.biospecimen.repository.impl.BiospecimenDaoHelper.getInstance()
			.getSiteCpsCondAqlForCps(List.of(SiteCpPair.make(1L, null, 1L)));
		ProtocolContentAccess access = ProtocolContentAccess.getInstance();
		assertTrue(access.isQueryAllowed(-1L, "select CollectionProtocol.id where " + sites));
		assertFalse(access.isQueryAllowed(1L, "select Specimen.spmnCollRecvDetails.recvTime where " + sites));
		assertFalse(access.isQueryAllowed(1L, "select Specimen.id where (" + sites + ") and Specimen.spmnCollRecvDetails.recvTime exists"));
		assertFalse(access.isQueryAllowed(1L, "select Participant.extensions.Hidden.requiredField where " + sites));
	}

	@Test
	public void disablingStandardQueryChecksDoesNotBypassContentPermissions() throws Exception {
		var op = new com.krishagni.catissueplus.core.de.events.ExecuteQueryEventOp();
		op.setCpId(1L); op.setDisableAccessChecks(true);
		op.setAql("select Specimen.spmnCollRecvDetails.recvTime where Specimen.id exists");
		var method = com.krishagni.catissueplus.core.de.services.impl.QueryServiceImpl.class
			.getDeclaredMethod("getQuery", op.getClass(), String.class);
		method.setAccessible(true);
		var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
			method.invoke(new com.krishagni.catissueplus.core.de.services.impl.QueryServiceImpl(), op, "test"));
		assertTrue(failure.getCause() instanceof OpenSpecimenException);
	}

	@Test
	public void newCollectionCannotBeAutomaticallyReceived() throws Exception {
		var pvDao = proxy(com.krishagni.catissueplus.core.administrative.repository.PermissibleValueDao.class, (method, args) -> {
			PermissibleValue pv = new PermissibleValue(); pv.setValue(Specimen.TO_BE_RECEIVED); return pv;
		});
		var daos = proxy(com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory.class, (method, args) -> pvDao);
		Specimen specimen = specimen();
		Field daoField = Specimen.class.getDeclaredField("daoFactory"); daoField.setAccessible(true); daoField.set(specimen, daos);
		specimen.setReceivedDetails(null, null);
		assertEquals(Specimen.TO_BE_RECEIVED, specimen.getReceivedQuality().getValue());
		assertNull(specimen.getReceivedTime()); assertNull(specimen.getReceivedUser()); assertNull(specimen.getReceivedQuantity());
	}

	@Test
	public void hiddenRequiredExtensionIsNeitherSavedNorValidated() throws Exception {
		DeObject hidden = new DeObject() {
			@Override public Long getFormId() { return 12L; }
			@Override public String getFormName() { return "Hidden"; }
			@Override public Long getObjectId() { return 4L; }
			@Override public String getEntityType() { return "SpecimenExtension"; }
			@Override public Long getCpId() { return 1L; }
			@Override public boolean isCpBased() { return true; }
			@Override public Long getEntityId() { return null; }
			@Override public void setAttrValues(java.util.Map<String, Object> values) { }
			@Override public boolean saveOrUpdate() { throw new AssertionError("Hidden required form was validated or saved"); }
		};
		ProtocolContentAccess access = new ProtocolContentAccess() {
			@Override public boolean isExtensionAllowed(DeObject object) { return false; }
		};
		setField(access, "rbacDaos", proxy(DaoFactory.class, (method, args) -> null));
		Field singleton = ProtocolContentAccess.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, access);
		BaseExtensionEntity entity = new BaseExtensionEntity() {
			@Override public String getEntityType() { return "SpecimenExtension"; }
			@Override public DeObject createExtension() { return hidden; }
		};
		entity.setExtension(hidden);
		entity.addOrUpdateExtension();
		assertSame(hidden, entity.getExtension());
		assertNull(entity.getExtensionRev());
		assertNull(ExtensionDetail.from(hidden));
		assertSame(hidden, DeObject.createExtension(new ExtensionDetail(), entity));
		ExtensionDetail malicious = new ExtensionDetail();
		ExtensionDetail.AttrDetail field = new ExtensionDetail.AttrDetail(); field.setName("hidden_required_field"); field.setValue("overwrite");
		malicious.setAttrs(List.of(field));
		assertThrows(OpenSpecimenException.class, () -> DeObject.createExtension(malicious, entity));
	}

	@Test(expected = OpenSpecimenException.class)
	public void formRecordListIsDeniedBeforeLoadingAnyRestrictedRecords() throws Exception {
		ProtocolContentAccess access = new ProtocolContentAccess() {
			@Override public boolean isFormAllowed(String type, Long id, Long formId) { return false; }
		};
		setField(access, "rbacDaos", proxy(DaoFactory.class, (method, args) -> null));
		Field singleton = ProtocolContentAccess.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, access);
		FormDao forms = proxy(FormDao.class, (method, args) -> {
			if (!method.equals("getFormByContext")) throw new AssertionError("Data loaded before authorization: " + method);
			FormSummary form = new FormSummary(); form.setFormId(12L); form.setEntityType("Specimen"); return form;
		});
		FormServiceImpl service = new FormServiceImpl(); service.setFormDao(forms);
		GetEntityFormRecordsOp op = new GetEntityFormRecordsOp(); op.setFormCtxtId(10L); op.setEntityId(4L);
		service.getEntityFormRecords(RequestEvent.wrap(op));
	}

	private Specimen specimen() {
		Specimen specimen = new Specimen(); specimen.setCollectionProtocol(cp); specimen.setLineage("New"); specimen.setCollectionStatus("Collected"); return specimen;
	}

	@Test public void scopeBlocksDirectCreationAndCollectionButPreservesEditingExistingChildren() {
		effectiveRoles = List.of(SpecimenCreationScopeTest.creator("Specimen", SpecimenCreationScopeTest.scope(1L, "New")));
		ProtocolContentAccess access = ProtocolContentAccess.getInstance();
		Specimen child = specimen(); child.setLineage("Derived");
		assertThrows(OpenSpecimenException.class, () -> access.ensureCreationAllowed(null, child));
		Specimen pending = specimen(); pending.setLineage("Derived"); pending.setCollectionStatus("Pending");
		assertThrows(OpenSpecimenException.class, () -> access.ensureCreationAllowed(pending, child));
		assertThrows(OpenSpecimenException.class, () -> pending.updateCollectionStatus("Collected"));
		access.ensureCreationAllowed(child, child); // Editing an already collected child is governed by Update.
		access.ensureCreationAllowed(null, specimen());
	}

	@Test public void updatePermissionAndSavedScopeCannotAuthorizeANewSpecimen() {
		var role = ProtocolContentAccessTest.role("Specimen", SpecimenCreationScopeTest.scope(1L, "New"));
		role.getAcl().forEach(acl -> acl.getOperations().forEach(op -> op.getOperation().setName("Update")));
		effectiveRoles = List.of(role);
		var access = ProtocolContentAccess.getInstance();
		assertThrows(OpenSpecimenException.class, () -> access.ensureCreationAllowed(null, specimen()));
		access.ensureCreationAllowed(specimen(), specimen());
	}

	@Test public void forbiddenPendingParentCannotBeCollectedImplicitlyForAnAllowedAliquot() throws Exception {
		effectiveRoles = List.of(SpecimenCreationScopeTest.creator("Specimen", SpecimenCreationScopeTest.scope(1L, "Aliquot")));
		Specimen parent = specimen(); parent.setLineage("Derived"); parent.setCollectionStatus("Pending");
		Specimen child = specimen(); child.setLineage("Aliquot"); child.setParentSpecimen(parent);
		var method = Specimen.class.getDeclaredMethod("autoCollectParentSpecimens", Specimen.class); method.setAccessible(true);
		var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(child, child));
		assertTrue(error.getCause() instanceof OpenSpecimenException);
		assertEquals("Pending", parent.getCollectionStatus());
	}

	@Test public void primaryCollectionDoesNotPrecreateForbiddenChildrenForLabelPrinting() throws Exception {
		effectiveRoles = List.of(SpecimenCreationScopeTest.creator("Specimen", SpecimenCreationScopeTest.scope(1L, "New")));
		var root = new com.krishagni.catissueplus.core.biospecimen.domain.SpecimenRequirement(); root.setActivityStatus("Active");
		var child = new com.krishagni.catissueplus.core.biospecimen.domain.SpecimenRequirement(); child.setLineage("Derived");
		child.setActivityStatus("Active"); root.addChildRequirement(child);
		var method = Specimen.class.getDeclaredMethod("createPendingSpecimens", root.getClass(), Specimen.class);
		method.setAccessible(true);
		assertTrue(((List<?>) method.invoke(specimen(), root, specimen())).isEmpty());
	}

	private static void setField(Object object, String name, Object value) throws Exception {
		Field field = ProtocolContentAccess.class.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
	}

	private interface Invocation { Object call(String method, Object[] args); }

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Invocation invocation) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> invocation.call(method.getName(), args));
	}
}
