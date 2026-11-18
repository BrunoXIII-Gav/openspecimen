package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.*;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krishagni.catissueplus.core.administrative.domain.*;
import com.krishagni.catissueplus.core.administrative.repository.PermissibleValueDao;
import com.krishagni.catissueplus.core.administrative.repository.UserDao;
import com.krishagni.catissueplus.core.biospecimen.domain.*;
import com.krishagni.catissueplus.core.biospecimen.events.*;
import com.krishagni.catissueplus.core.biospecimen.repository.SpecimenDao;
import com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenWorkflowService;
import com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenNavigationService;
import com.krishagni.catissueplus.core.common.events.UserSummary;
import com.krishagni.catissueplus.core.common.service.ConfigurationService;
import com.krishagni.catissueplus.core.common.service.impl.EventPublisher;
import com.krishagni.catissueplus.core.common.util.*;
import com.krishagni.rbac.domain.*;
import com.krishagni.rbac.repository.*;
import static org.junit.Assert.*;

public class SpecimenWorkflowTest {
	private final Map<Long, Specimen> specimens = new LinkedHashMap<>();
	private final List<Specimen> saved = new ArrayList<>();
	private final List<SpecimenDetail> processed = new ArrayList<>();
	private final Map<Field, Object> restore = new LinkedHashMap<>();
	private SpecimenWorkflowService service;
	private com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory bio;
	private User user;
	private CollectionProtocol cp;
	private boolean read = true, update = true, receipt = true;
	private boolean failProcessing;
	private SpecimenNavigationService navigationService;
	private int navigationReads;
	private com.krishagni.catissueplus.core.biospecimen.repository.CprListCriteria navigationCriteria;
	private boolean mrnRestricted;

	@Before public void setup() throws Exception {
		Institute institute = new Institute(); institute.setId(1L);
		Site site = new Site(); site.setId(1L); site.setInstitute(institute);
		user = new User(); user.setId(7L); user.setType(User.Type.NONE); user.setInstitute(institute);
		AuthUtil.setCurrentUser(user);
		cp = new CollectionProtocol() { @Override public Set<Site> getRepositories() { return Set.of(site); } };
		cp.setId(1L); cp.setShortTitle("CCIN");
		RoleDao roleDao = proxy(RoleDao.class, (name, args) -> {
			var rule = ProtocolContentAccessTest.rule(1L, receipt ? "receipt" : "collection");
			return name.equals("hasContentPolicies") ? true : List.of(ProtocolContentAccessTest.role("Specimen", rule));
		});
		SubjectDao subjects = proxy(SubjectDao.class, (name, args) -> {
			if (!name.equals("getAccessList")) throw new AssertionError(name);
			String[] resources = args[2] instanceof String[] ? (String[]) args[2] : new String[] {(String) args[2]};
			String[] operations = (String[]) args[3];
			if (!Arrays.asList(resources).contains("Specimen")) return List.of();
			if ((!read || !Arrays.asList(operations).contains("Read")) && (!update || !Arrays.asList(operations).contains("Update"))) return List.of();
			SubjectAccess access = new SubjectAccess(); access.setSite(site); access.setCollectionProtocol(cp); return List.of(access);
		});
		var rbac = proxy(DaoFactory.class, (name, args) -> name.equals("getRoleDao") ? roleDao : subjects);
		SpecimenDao dao = proxy(SpecimenDao.class, (name, args) -> {
			if (name.equals("getById")) return specimens.get(args[0]);
			if (name.equals("lockForQuantityUpdate")) return null;
			if (name.equals("saveOrUpdate")) { saved.add((Specimen) args[0]); return null; }
			throw new AssertionError(name);
		});
		PermissibleValueDao pvs = proxy(PermissibleValueDao.class, (name, args) -> {
			if (!Set.of("Acceptable", "To be Received").contains(args[1])) return null;
			PermissibleValue value = new PermissibleValue(); value.setValue((String) args[1]); return value;
		});
		UserDao users = proxy(UserDao.class, (name, args) -> args[0].equals(7L) ? user : null);
		bio = proxy(com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory.class, (name, args) -> {
			if (name.equals("getSpecimenDao")) return dao;
			if (name.equals("getUserDao")) return users;
			if (name.equals("getPermissibleValueDao")) return pvs;
			if (name.equals("getVisitsDao")) return proxy(com.krishagni.catissueplus.core.biospecimen.repository.VisitsDao.class,
				(method, values) -> values[0].equals(43L) ? specimens.get(212L).getVisit() : null);
			if (name.equals("getCprDao")) return proxy(com.krishagni.catissueplus.core.biospecimen.repository.CollectionProtocolRegistrationDao.class, (method, values) -> {
				navigationReads++;
				if (method.equals("getCprs")) { navigationCriteria = (com.krishagni.catissueplus.core.biospecimen.repository.CprListCriteria) values[0]; return List.of(specimens.get(212L).getRegistration()); }
				if (method.equals("getCprCount")) return 1L;
				if (method.equals("getById")) return values[0].equals(32L) ? specimens.get(212L).getRegistration() : null;
				throw new AssertionError(method);
			});
			throw new AssertionError(name);
		});
		var constructor = AccessCtrlMgr.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccessCtrlMgr access = constructor.newInstance(); set(access, "daoFactory", rbac); set(access, "bsDaoFactory", bio);
		replace(AccessCtrlMgr.class, "instance", access);
		ProtocolContentAccess content = new ProtocolContentAccess(); set(content, "rbacDaos", rbac);
		replace(ProtocolContentAccess.class, "instance", content);
		ConfigUtil config = new ConfigUtil(); set(config, "cfgSvc", proxy(ConfigurationService.class, (name, args) -> mrnRestricted));
		replace(ConfigUtil.class, "instance", config);
		Field publisher = EventPublisher.class.getDeclaredField("publisher"); publisher.setAccessible(true);
		restore.put(publisher, publisher.get(EventPublisher.getInstance()));
		EventPublisher.getInstance().setApplicationEventPublisher(event -> {});
		service = new SpecimenWorkflowService() {
			@Override protected void collectMatrixSpecimens(List<SpecimenDetail> inputs) {
				assertTrue("receipt must be reconciled before processing", specimens.get(212L).isReceived());
				if (failProcessing) throw com.krishagni.catissueplus.core.common.errors.OpenSpecimenException.userError(com.krishagni.rbac.common.errors.RbacErrorCode.ACCESS_DENIED);
				processed.addAll(inputs);
			}
			@Override protected Specimen newReceiptSpecimen() {
				Specimen result = new Specimen();
				try { set(result, "daoFactory", bio); } catch (Exception e) { throw new RuntimeException(e); }
				return result;
			}
		};
		set(service, "daoFactory", bio);
		navigationService = new SpecimenNavigationService(); set(navigationService, "daoFactory", bio);
		specimens.put(212L, specimen(212L));
	}

	@After public void cleanup() throws Exception {
		AuthUtil.clearCurrentUser();
		for (var entry : restore.entrySet()) entry.getKey().set(Modifier.isStatic(entry.getKey().getModifiers()) ? null : EventPublisher.getInstance(), entry.getValue());
	}

	@Test public void specimenContextRequiresReadAndContainsNoParticipantOrVisitDetails() {
		var response = service.getContext(212L); response.throwErrorIfUnsuccessful();
		Map<?, ?> cpr = (Map<?, ?>) response.getPayload().get("cpr"), visit = (Map<?, ?>) response.getPayload().get("visit");
		assertEquals(Set.of("id", "cpId", "cpShortTitle", "ppid", "contextOnly", "participant"), cpr.keySet());
		assertEquals(Map.of("pmis", List.of()), cpr.get("participant"));
		assertEquals(Set.of("id", "cpId", "cprId", "contextOnly", "status", "eventId", "eventLabel", "eventCode"), visit.keySet());
		read = false;
		assertThrows(RuntimeException.class, () -> service.getContext(212L).throwErrorIfUnsuccessful());
	}

	@Test public void receiptNeedsReadUpdateAndReceiptButDoesNotNeedCreateParticipantOrVisitPermissions() {
		assertFalse(ProtocolContentAccess.getInstance().isCreationAllowed(1L, "New"));
		var response = service.receive(List.of(input(212L))); response.throwErrorIfUnsuccessful();
		Specimen specimen = specimens.get(212L);
		assertEquals("Acceptable", specimen.getReceivedQuality().getValue());
		assertEquals("original collection", specimen.getCollectionComments());
		assertEquals(new Date(10), specimen.getCollectionTime());
		assertEquals("S-212", specimen.getLabel());
		assertEquals("Collected", specimen.getCollectionStatus());
		assertEquals(new BigDecimal("10"), specimen.getInitialQuantity());
		assertEquals(1, saved.size());
	}

	@Test public void missingReceiptOrUpdateOrReadRejectsBeforeAnyWrite() {
		for (String permission : List.of("read", "update", "receipt")) {
			read = !permission.equals("read"); update = !permission.equals("update"); receipt = !permission.equals("receipt");
			assertThrows(RuntimeException.class, () -> service.receive(List.of(input(212L))).throwErrorIfUnsuccessful());
			assertFalse(specimens.get(212L).isReceived()); assertTrue(saved.isEmpty());
		}
	}

	@Test public void failedBatchAndRepeatedReceiptDoNotOverwritePreviousData() {
		specimens.put(213L, specimen(213L));
		var invalid = input(213L); invalid.getReceivedEvent().setTime(null);
		assertThrows(RuntimeException.class, () -> service.receive(List.of(input(212L), invalid)).throwErrorIfUnsuccessful());
		assertTrue(saved.isEmpty()); assertFalse(specimens.get(212L).isReceived());
		service.receive(List.of(input(212L))).throwErrorIfUnsuccessful();
		assertThrows(RuntimeException.class, () -> service.receive(List.of(input(212L))).throwErrorIfUnsuccessful());
		assertEquals(1, saved.size());
	}

	@Test public void quantitiesReconcileWithoutChangingInitialQuantityAndDuplicateIdsAreRejected() {
		var input = input(212L); input.getReceivedEvent().setReceivedQtyDifferent(true);
		input.getReceivedEvent().setReceivedQty(new BigDecimal("9")); input.getReceivedEvent().setReceivedQtyReason("Transport loss");
		service.receive(List.of(input)).throwErrorIfUnsuccessful();
		assertEquals(new BigDecimal("10"), specimens.get(212L).getInitialQuantity());
		assertEquals(new BigDecimal("7"), specimens.get(212L).getAvailableQuantity());
		assertThrows(RuntimeException.class, () -> service.receive(List.of(input, input)).throwErrorIfUnsuccessful());
	}

	@Test public void wrongProtocolAndNonPrimaryOrUncollectedSelectionsCannotBeReceived() {
		assertThrows(RuntimeException.class, () -> service.getReceiptMatrix(2L, null, List.of(212L)).throwErrorIfUnsuccessful());
		specimens.get(212L).setCollectionStatus("Pending");
		assertThrows(RuntimeException.class, () -> service.receive(List.of(input(212L))).throwErrorIfUnsuccessful());
		specimens.get(212L).setCollectionStatus("Collected"); specimens.get(212L).setLineage("Derived");
		assertThrows(RuntimeException.class, () -> service.receive(List.of(input(212L))).throwErrorIfUnsuccessful());
		assertTrue(saved.isEmpty());
	}

	@Test public void receiptPayloadRejectsOtherSpecimenFields() {
		assertThrows(Exception.class, () -> new ObjectMapper().readValue("{\"id\":212,\"status\":\"Collected\"}", SpecimenReceiptInput.class));
		assertThrows(Exception.class, () -> new ObjectMapper().readValue("{\"id\":212,\"collectionEvent\":{}}", SpecimenReceiptInput.class));
	}

	@Test public void matrixShowsOnlyPendingPrimariesAndDoesNotExposeHiddenCollectionOrParticipantData() {
		Specimen child = specimen(213L); child.setLineage("Derived"); specimens.put(213L, child);
		Specimen received = specimen(214L); PermissibleValue quality = new PermissibleValue(); quality.setValue("Acceptable");
		received.setReceivedQuality(quality); specimens.put(214L, received);
		var response = service.getReceiptMatrix(1L, null, List.of(212L, 213L, 214L)); response.throwErrorIfUnsuccessful();
		assertEquals(2, response.getPayload().get("skipped"));
		var rows = (List<?>) response.getPayload().get("specimens"); assertEquals(1, rows.size());
		Map<?, ?> row = (Map<?, ?>) rows.get(0);
		assertNull(row.get("collectionEvent")); assertFalse(row.containsKey("participant")); assertFalse(row.containsKey("ppid"));
		assertFalse(row.containsKey("visit")); assertFalse(row.containsKey("extensionDetail"));
	}

	@Test public void sharedMatrixAcceptsReceiptWithoutCreatingOrChangingCollection() {
		SpecimenMatrixInput input = new SpecimenMatrixInput(); input.setCpId(1L); input.setReceipts(List.of(input(212L)));
		service.saveMatrix(input).throwErrorIfUnsuccessful();
		assertTrue(specimens.get(212L).isReceived()); assertTrue(processed.isEmpty());
		assertEquals("original collection", specimens.get(212L).getCollectionComments());
	}

	@Test public void sharedMatrixScopesSelectionsAndDoesNotTreatExistingPrimariesAsUpdates() {
		SpecimenMatrixInput input = new SpecimenMatrixInput(); input.setCpId(2L); input.setReceipts(List.of(input(212L)));
		assertThrows(RuntimeException.class, () -> service.saveMatrix(input).throwErrorIfUnsuccessful());
		assertTrue(saved.isEmpty()); input.setCpId(1L); input.setReceipts(List.of());
		SpecimenDetail reference = new SpecimenDetail(); reference.setId(212L); reference.setUpdate(true);
		input.setSpecimens(List.of(reference));
		assertThrows(RuntimeException.class, () -> service.saveMatrix(input).throwErrorIfUnsuccessful());
		assertTrue(saved.isEmpty()); assertTrue(processed.isEmpty());
	}

	@Test public void sharedMatrixDelegatesProcessingAndPropagatesFailureForTransactionRollback() {
		SpecimenMatrixInput input = new SpecimenMatrixInput(); input.setCpId(1L); input.setReceipts(List.of(input(212L)));
		SpecimenDetail reference = new SpecimenDetail(); reference.setId(212L); input.setSpecimens(List.of(reference));
		failProcessing = true;
		assertFalse(service.saveMatrix(input).isSuccessful()); assertTrue(processed.isEmpty());
		// In-memory objects are not a database: the woven wiring test verifies the transaction rollback.
	}

	@Test public void matrixContextRequiresSpecimenReadButNotVisitOrParticipantRead() {
		var response = service.getMatrixContext(1L, 43L); response.throwErrorIfUnsuccessful();
		assertEquals(1L, response.getPayload().get("cpId"));
		assertFalse(((Map<?, ?>) response.getPayload().get("visit")).containsKey("visitDate"));
		assertFalse(((Map<?, ?>) response.getPayload().get("cpr")).containsKey("firstName"));
		read = false;
		assertThrows(RuntimeException.class, () -> service.getMatrixContext(1L, 43L).throwErrorIfUnsuccessful());
	}

	@Test public void sharedMatrixCannotBypassInitialVisitCollection() {
		specimens.get(212L).getVisit().setStatus("Pending");
		specimens.get(212L).setCollectionStatus("Pending");
		SpecimenDetail pending = new SpecimenDetail(); pending.setId(212L); pending.setStatus("Collected");
		SpecimenMatrixInput input = new SpecimenMatrixInput(); input.setCpId(1L); input.setSpecimens(List.of(pending));
		assertFalse(service.saveMatrix(input).isSuccessful()); assertTrue(processed.isEmpty()); assertTrue(saved.isEmpty());
	}

	@Test public void specimenNavigationReturnsOnlyReferencesWithoutParticipantOrVisitRead() {
		var registration = specimens.get(212L).getRegistration(); registration.setPpid("PRIVATE-PPID");
		registration.getParticipant().setFirstName("PRIVATE-NAME");
		specimens.get(212L).getVisit().setVisitDate(new Date(123));
		var list = navigationService.getRegistrations(1L, 0, 50); list.throwErrorIfUnsuccessful();
		Map<?, ?> row = (Map<?, ?>) ((List<?>) list.getPayload().get("registrations")).get(0);
		assertEquals(Set.of("id", "cpId", "cpShortTitle", "ppid", "contextOnly", "participant"), row.keySet());
		assertEquals(Map.of("pmis", List.of()), row.get("participant"));
		assertEquals(Long.valueOf(1L), navigationCriteria.cpId()); assertEquals(51, navigationCriteria.maxResults());
		assertNotNull(navigationCriteria.siteCps()); assertFalse(navigationCriteria.siteCps().isEmpty());
		var detail = navigationService.getRegistration(1L, 32L, 0, 50); detail.throwErrorIfUnsuccessful();
		Map<?, ?> visit = (Map<?, ?>) ((List<?>) detail.getPayload().get("visits")).get(0);
		assertEquals(Set.of("id", "cpId", "cprId", "contextOnly", "status", "eventId", "eventLabel", "eventCode"), visit.keySet()); assertEquals(43L, visit.get("id"));
		assertEquals("Sample collection", visit.get("eventLabel")); assertFalse(visit.containsKey("visitDate")); assertFalse(visit.containsKey("name"));
		assertEquals("PRIVATE-PPID", ((Map<?, ?>) detail.getPayload().get("cpr")).get("ppid"));
		assertFalse(detail.getPayload().toString().contains("PRIVATE-NAME"));
	}

	@Test public void navigationRejectsMissingSpecimenReadAndInvalidPagesBeforeQuerying() {
		read = false;
		assertFalse(navigationService.getRegistrations(1L, 0, 50).isSuccessful()); assertEquals(0, navigationReads);
		read = true;
		assertFalse(navigationService.getRegistrations(1L, -1, 50).isSuccessful());
		assertFalse(navigationService.getRegistrations(1L, 0, 1001).isSuccessful()); assertEquals(0, navigationReads);
	}

	@Test public void navigationScopesProtocolSiteAndMrnWithoutExposingFilterableClinicalFields() {
		assertFalse(navigationService.getRegistration(2L, 32L, 0, 50).isSuccessful());
		assertFalse(navigationService.getRegistration(1L, 999L, 0, 50).isSuccessful());
		mrnRestricted = true; navigationService.getRegistrations(1L, 0, 50).throwErrorIfUnsuccessful();
		assertTrue(navigationCriteria.useMrnSites()); assertNull(navigationCriteria.name()); assertNull(navigationCriteria.ppid());
		CollectionProtocol inaccessible = new CollectionProtocol() { @Override public Set<Site> getRepositories() { return Set.of(); } };
		inaccessible.setId(1L); inaccessible.setShortTitle("OTHER SITE"); specimens.get(212L).getRegistration().setCollectionProtocol(inaccessible);
		assertFalse(navigationService.getRegistration(1L, 32L, 0, 50).isSuccessful());
	}

	@Test public void ppidListSupportsOnlyItsAuthorizedIdentifierFilterSortAndCount() {
		var response = navigationService.getRegistrations(1L, 0, 101, "NRC-0022", false, true);
		response.throwErrorIfUnsuccessful(); assertEquals(1L, response.getPayload().get("size"));
		assertEquals("NRC-0022", navigationCriteria.ppid()); assertEquals("cpr.ppid", navigationCriteria.orderBy()); assertTrue(navigationCriteria.asc());
		assertNull(navigationCriteria.name()); assertNull(navigationCriteria.dob()); assertFalse(navigationCriteria.includePhi());
	}

	@Test public void nativeVisitSpecimenQueryDoesNotRequireParticipantOrVisitRead() throws Exception {
		var visit = specimens.get(212L).getVisit(); var event = visit.getCpEvent();
		for (int index = 1; index <= 3; index++) {
			var requirement = new SpecimenRequirement(); requirement.setId(100L + index); requirement.setLineage("New");
			requirement.setName("Blood " + index); requirement.setSortOrder(index); requirement.setActivityStatus("Active");
			requirement.setCollectionProtocolEvent(event); event.getSpecimenRequirements().add(requirement);
		}
		var nativeService = new com.krishagni.catissueplus.core.biospecimen.services.impl.CollectionProtocolRegistrationServiceImpl();
		set(nativeService, "daoFactory", bio);
		var criteria = new VisitSpecimensQueryCriteria(); criteria.setCprId(32L); criteria.setVisitId(43L);
		var response = nativeService.getSpecimens(com.krishagni.catissueplus.core.common.events.RequestEvent.wrap(criteria));
		response.throwErrorIfUnsuccessful(); assertEquals(3, response.getPayload().size());
		assertTrue(response.getPayload().stream().allMatch(specimen -> "New".equals(specimen.getLineage())));
		read = false;
		assertFalse(nativeService.getSpecimens(com.krishagni.catissueplus.core.common.events.RequestEvent.wrap(criteria)).isSuccessful());
	}

	@Test public void nativeVisitReturnsCollectedPrimariesWithoutParticipantRead() throws Exception {
		var visit = specimens.get(212L).getVisit();
		for (long id : List.of(213L, 214L)) {
			var specimen = specimen(id); specimen.setVisit(visit); specimens.put(id, specimen);
		}
		visit.setSpecimens(new HashSet<>(specimens.values()));
		try (var context = new org.springframework.context.support.GenericApplicationContext()) {
			context.getBeanFactory().registerSingleton("formInfoCache", new com.krishagni.catissueplus.core.de.domain.FormInfoCache() {
				@Override public Map<String, Object> getFormInfo(boolean cpBased, String type, Long id) { return null; }
				@Override public edu.common.dynamicextensions.domain.nui.Container getForm(String name) { return null; }
			});
			context.refresh(); replace(com.krishagni.catissueplus.core.common.OpenSpecimenAppCtxProvider.class, "context", context);
			var nativeService = new com.krishagni.catissueplus.core.biospecimen.services.impl.CollectionProtocolRegistrationServiceImpl(); set(nativeService, "daoFactory", bio);
			var criteria = new VisitSpecimensQueryCriteria(); criteria.setCprId(32L); criteria.setVisitId(43L);
			var response = nativeService.getSpecimens(com.krishagni.catissueplus.core.common.events.RequestEvent.wrap(criteria));
			response.throwErrorIfUnsuccessful(); assertEquals(3, response.getPayload().size());
			assertTrue(response.getPayload().stream().allMatch(specimen -> "Collected".equals(specimen.getStatus()) && "New".equals(specimen.getLineage())));
			assertEquals(Set.of("S-212", "S-213", "S-214"), response.getPayload().stream().map(SpecimenInfo::getLabel).collect(java.util.stream.Collectors.toSet()));
		}
	}

	private Specimen specimen(Long id) {
		var cpr = new CollectionProtocolRegistration(); cpr.setId(32L); cpr.setCollectionProtocol(cp); cpr.setParticipant(new Participant());
		var visit = new Visit(); visit.setId(43L); visit.setRegistration(cpr);
		var event = new CollectionProtocolEvent(); event.setId(2L); event.setCollectionProtocol(cp); event.setEventLabel("Sample collection"); event.setCode("COLL");
		visit.setCpEvent(event); visit.setStatus("Complete");
		cpr.getVisits().add(visit);
		Specimen result = new Specimen() {
			@Override public void setExtension(com.krishagni.catissueplus.core.de.domain.DeObject extension) {
				// Plain-Java fixtures need the injection performed by AspectJ in the application.
				if (extension != null) try {
					set(extension, "formInfoCache", com.krishagni.catissueplus.core.common.OpenSpecimenAppCtxProvider.getBean("formInfoCache"));
				} catch (Exception e) { throw new RuntimeException(e); }
				super.setExtension(extension);
			}
		};
		result.setId(id); result.setLabel("S-" + id); result.setVisit(visit); result.setCollectionProtocol(cp);
		result.setLineage("New"); result.setActivityStatus("Active"); result.setCollectionStatus("Collected");
		PermissibleValue type = new PermissibleValue(); type.setValue("Blood"); result.setSpecimenType(type);
		PermissibleValue specimenClass = new PermissibleValue(); specimenClass.setValue("Fluid"); result.setSpecimenClass(specimenClass);
		result.setInitialQuantity(new BigDecimal("10")); result.setAvailableQuantity(new BigDecimal("8"));
		result.setCollectionTime(new Date(10)); result.setCollectionComments("original collection");
		return result;
	}
	private SpecimenReceiptInput input(Long id) {
		SpecimenReceiptInput result = new SpecimenReceiptInput(); result.setId(id);
		ReceivedEventDetail event = new ReceivedEventDetail(); event.setReceivedQuality("Acceptable"); event.setTime(new Date(100));
		UserSummary receiver = new UserSummary(); receiver.setId(7L); event.setUser(receiver); result.setReceivedEvent(event); return result;
	}
	private void replace(Class<?> type, String name, Object value) throws Exception {
		Field field = type.getDeclaredField(name); field.setAccessible(true); restore.put(field, field.get(null)); field.set(null, value);
	}
	private static void set(Object object, String name, Object value) throws Exception {
		Class<?> type = object.getClass();
		while (type != null) {
			try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value); return; }
			catch (NoSuchFieldException e) { type = type.getSuperclass(); }
		}
		throw new NoSuchFieldException(name);
	}
	private interface Invocation { Object call(String name, Object[] args); }
	@SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation call) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> call.call(method.getName(), args));
	}
}
