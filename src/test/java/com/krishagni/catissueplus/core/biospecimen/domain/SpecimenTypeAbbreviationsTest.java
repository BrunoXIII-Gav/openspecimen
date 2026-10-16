package com.krishagni.catissueplus.core.biospecimen.domain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.krishagni.catissueplus.core.administrative.domain.PermissibleValue;
import com.krishagni.catissueplus.core.administrative.repository.PermissibleValueDao;
import com.krishagni.catissueplus.core.biospecimen.label.specimen.ParentSpecimenLabelToken;
import com.krishagni.catissueplus.core.biospecimen.label.specimen.ParentSpecimenUniqueIdLabelToken;
import com.krishagni.catissueplus.core.biospecimen.label.specimen.SpecimenTypeAbbrUniqueIdLabelToken;
import com.krishagni.catissueplus.core.biospecimen.label.specimen.SpecimenTypeLabelToken;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.common.TransactionCache;
import com.krishagni.catissueplus.core.common.domain.DefaultLabelTmplTokenRegistrar;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.repository.UniqueIdGenerator;
import com.krishagni.catissueplus.core.common.service.impl.LabelGeneratorImpl;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenInfo;
import com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenServiceImpl;
import com.krishagni.catissueplus.core.common.domain.PrintItem;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.Assert.*;

public class SpecimenTypeAbbreviationsTest {

	private Map<Long, CpWorkflowConfig> workflows;
	private Map<String, Long> counters;
	private SpecimenTypeAbbrUniqueIdLabelToken sequence;
	private LabelGeneratorImpl labels;
	private PermissibleValue plasma;
	private PermissibleValue serum;

	@Before
	public void setUp() throws Exception {
		TransactionSynchronizationManager.initSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		workflows = new HashMap<>();
		TransactionCache.getInstance().put("cpWorkflows", workflows);
		counters = new HashMap<>();
		plasma = type(21L, "Plasma", "Pl");
		serum = type(22L, "Serum", "Se");

		UniqueIdGenerator ids = proxy(UniqueIdGenerator.class, (method, args) ->
			counters.merge(args[0] + ":" + args[1], 1L, Long::sum));
		DaoFactory daos = proxy(DaoFactory.class, (method, args) -> {
			assertEquals("getUniqueIdGenerator", method);
			return ids;
		});
		sequence = new SpecimenTypeAbbrUniqueIdLabelToken();
		Field field = SpecimenTypeAbbrUniqueIdLabelToken.class.getDeclaredField("daoFactory");
		field.setAccessible(true);
		field.set(sequence, daos);
		DefaultLabelTmplTokenRegistrar tokens = new DefaultLabelTmplTokenRegistrar();
		tokens.register(new ParentSpecimenLabelToken());
		ParentSpecimenUniqueIdLabelToken parentSequence = new ParentSpecimenUniqueIdLabelToken();
		Field parentDao = ParentSpecimenUniqueIdLabelToken.class.getDeclaredField("daoFactory");
		parentDao.setAccessible(true);
		parentDao.set(parentSequence, daos);
		tokens.register(parentSequence);
		tokens.register(new SpecimenTypeLabelToken());
		tokens.register(sequence);
		labels = new LabelGeneratorImpl();
		labels.setTokenRegistrar(tokens);
	}

	@After
	public void tearDown() {
		TransactionCache.getInstance().clear(0);
		TransactionSynchronizationManager.clearSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	public void sameTypeCanHaveDifferentAbbreviationsInDifferentProtocols() {
		configure(1L, Map.of("21", "P"));
		configure(2L, Map.of("21", "PLAS"));
		configure(3L, Map.of());
		assertEquals("NRC-0001.1.1.P1", label(child(1L, 100L, plasma)));
		assertEquals("NRC-0001.1.1.PLAS1", label(child(2L, 200L, plasma)));
		assertEquals("NRC-0001.1.1.Pl1", label(child(3L, 300L, plasma)));
		assertEquals("Pl", plasma.getProps().get("abbreviation"));
	}

	@Test
	public void eachAbbreviationHasAnIndependentCounterWithinTheSameParent() {
		configure(1L, Map.of("21", "P", "22", "L"));
		assertEquals("NRC-0001.1.1.P1", label(child(1L, 100L, plasma)));
		assertEquals("NRC-0001.1.1.L1", label(child(1L, 100L, serum)));
		assertEquals("NRC-0001.1.1.P2", label(child(1L, 100L, plasma)));
		assertEquals("NRC-0001.1.1.L2", label(child(1L, 100L, serum)));
		assertEquals("NRC-0001.1.1.P1", label(child(1L, 101L, plasma)));
	}

	@Test
	public void typesWithTheSameEffectiveAbbreviationShareTheirCounter() {
		configure(1L, Map.of("21", "L", "22", "L"));
		assertEquals("NRC-0001.1.1.L1", label(child(1L, 100L, plasma)));
		assertEquals("NRC-0001.1.1.L2", label(child(1L, 100L, serum)));
	}

	@Test
	public void unconfiguredProtocolsKeepExistingSequenceKeysAndValues() {
		configure(1L, Map.of());
		Specimen specimen = child(1L, 100L, plasma);
		assertEquals("NRC-0001.1.1.Pl1", label(specimen));
		assertEquals(Long.valueOf(1), counters.get("PARENT_SPMN_SP_TYPE_ABBR_UID:100_Pl"));
		configure(1L, Map.of("21", "Pl"));
		assertEquals("NRC-0001.1.1.Pl2", label(specimen));
		configure(1L, Map.of());
		assertEquals("NRC-0001.1.1.Pl3", label(specimen));
	}

	@Test
	public void labelBasedKeysRemainIsolatedByProtocol() {
		configure(1L, Map.of("21", "P"));
		configure(2L, Map.of("21", "P"));
		Specimen first = child(1L, 100L, plasma);
		Specimen second = child(2L, 200L, plasma);
		first.getCollectionProtocol().setLabelSequenceKey(CollectionProtocol.LabelSequenceKey.LABEL);
		second.getCollectionProtocol().setLabelSequenceKey(CollectionProtocol.LabelSequenceKey.LABEL);
		assertEquals("NRC-0001.1.1.P1", label(first));
		assertEquals("NRC-0001.1.1.P1", label(second));
		assertEquals("NRC-0001.1.1.P2", label(first));
	}

	@Test
	public void overrideIsUsedForEverySupportedSequenceScope() {
		configure(1L, Map.of("21", "P"));
		Specimen specimen = child(1L, 100L, plasma);
		for (String scope : new String[] {"registration", "visit", "parent_specimen", "primary_specimen"}) {
			assertEquals("1", sequence.getReplacement(specimen, "0", "output_one", scope));
			assertEquals("2", sequence.getReplacement(specimen, "0", "output_one", scope));
		}
		assertTrue(counters.keySet().stream().allMatch(key -> key.endsWith("_P")));
	}

	@Test
	public void configuringAnOverrideDoesNotRelabelExistingSpecimens() {
		configure(1L, Map.of("21", "P"));
		Specimen specimen = child(1L, 100L, plasma);
		specimen.setLabel("HISTORICAL-Pl7");
		specimen.setLabelIfEmpty();
		assertEquals("HISTORICAL-Pl7", specimen.getLabel());
		assertTrue(counters.isEmpty());
	}

	@Test
	public void validatesAndNormalizesConfigurationWithoutChangingOtherProperties() {
		Map<String, Object> data = Map.of("abbreviations", Map.of("21", " P ", "22", " "));
		Map<String, Object> result = SpecimenTypeAbbreviations.validate(data, pvDao());
		assertEquals(Map.of("version", 1, "abbreviations", Map.of("21", "P")), result);
		assertEquals("Pl", plasma.getProps().get("abbreviation"));
	}

	@Test
	public void rejectsInvalidConfigurationAndNonSpecimenTypes() {
		for (Map<String, Object> invalid : new Map[] {
			Map.of(), Map.of("abbreviations", "P"),
			Map.of("version", 2, "abbreviations", Map.of()),
			Map.of("abbreviations", Map.of("unknown", "P")),
			Map.of("abbreviations", Map.of("999999999999999999999999", "P")),
			Map.of("abbreviations", Map.of("21", 12)),
			Map.of("abbreviations", Map.of("21", "P".repeat(256))),
			Map.of("abbreviations", Map.of("99", "P"))
		}) {
			assertThrows(OpenSpecimenException.class, () -> SpecimenTypeAbbreviations.validate(invalid, pvDao()));
		}
		plasma.setAttribute("gender");
		assertThrows(OpenSpecimenException.class, () -> SpecimenTypeAbbreviations.validate(
			Map.of("abbreviations", Map.of("21", "P")), pvDao()));
		plasma.setAttribute("specimen_type");
		plasma.setParent(null);
		assertThrows(OpenSpecimenException.class, () -> SpecimenTypeAbbreviations.validate(
			Map.of("abbreviations", Map.of("21", "P")), pvDao()));
	}

	@Test
	public void overridesSurviveWorkflowSerializationAndTypeRenaming() {
		configure(1L, Map.of("21", "P"));
		String json = workflows.get(1L).getWorkflowsJson();
		CpWorkflowConfig restored = new CpWorkflowConfig();
		restored.setWorkflowsJson(json);
		workflows.put(1L, restored);
		plasma.setValue("Renamed Plasma");
		assertEquals("NRC-0001.1.1.P1", label(child(1L, 100L, plasma)));
	}

	@Test
	public void intermediateSelectionAppliesOnlyToMatchingDerivativesInItsProtocol() {
		configure(1L, Map.of("21", "P"));
		configure(2L, Map.of("21", "P"));
		markIntermediate(1L, "21");
		Specimen specimen = child(1L, 100L, plasma);
		specimen.setLineage(Specimen.DERIVED);
		assertTrue(IntermediateSpecimens.isIntermediate(specimen));
		specimen.setSpecimenType(serum);
		assertFalse(IntermediateSpecimens.isIntermediate(specimen));
		specimen.setSpecimenType(plasma);
		specimen.setLineage(Specimen.ALIQUOT);
		assertFalse(IntermediateSpecimens.isIntermediate(specimen));
		specimen.setLineage(Specimen.NEW);
		assertFalse(IntermediateSpecimens.isIntermediate(specimen));
		Specimen otherCp = child(2L, 200L, plasma);
		otherCp.setLineage(Specimen.DERIVED);
		assertFalse(IntermediateSpecimens.isIntermediate(otherCp));
	}

	@Test
	public void intermediateLabelStillUsesTheConfiguredDerivativeFormat() throws Exception {
		configure(1L, Map.of());
		markIntermediate(1L, "21");
		Specimen specimen = child(1L, 100L, plasma);
		specimen.setLineage(Specimen.DERIVED);
		specimen.getCollectionProtocol().setDerivativeLabelFormat("%PSPEC_LABEL%-PROCESS-%PSPEC_UID%");
		Field generator = Specimen.class.getDeclaredField("labelGenerator");
		generator.setAccessible(true);
		generator.set(specimen, labels);
		specimen.setLabelIfEmpty();
		assertEquals("NRC-0001.1.1-PROCESS-1", specimen.getLabel());
		markIntermediate(1L);
		specimen.setLabelIfEmpty();
		assertEquals("NRC-0001.1.1-PROCESS-1", specimen.getLabel());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void automaticPrintingSkipsTheIntermediateButStillIncludesItsAliquots() throws Exception {
		configure(1L, Map.of());
		markIntermediate(1L, "21");
		Specimen intermediate = child(1L, 100L, plasma);
		intermediate.setId(101L);
		intermediate.setLineage(Specimen.DERIVED);
		intermediate.setLabel("REAL-INTERNAL-LABEL");
		Specimen parent = intermediate.getParentSpecimen();
		parent.setLineage(Specimen.NEW);
		Specimen aliquot = child(1L, 101L, plasma);
		aliquot.setId(102L);
		aliquot.setLineage(Specimen.ALIQUOT);
		aliquot.setParentSpecimen(intermediate);
		parent.getChildCollection().add(intermediate);
		intermediate.getChildCollection().add(aliquot);
		parent.setPrintLabel(true);
		intermediate.setPrintLabel(true);
		aliquot.setPrintLabel(true);
		assertFalse(intermediate.isPrintLabel());
		assertTrue(aliquot.isPrintLabel());
		Method method = SpecimenServiceImpl.class.getDeclaredMethod("getSpecimenPrintItems", java.util.Collection.class);
		method.setAccessible(true);
		List<PrintItem<Specimen>> items = (List<PrintItem<Specimen>>) method.invoke(new SpecimenServiceImpl(), List.of(parent));
		assertEquals(List.of(parent, aliquot), items.stream().map(PrintItem::getObject).toList());
		assertEquals("REAL-INTERNAL-LABEL", intermediate.getLabel());
		// Explicit printing constructs print items directly and remains available.
		assertSame(intermediate, PrintItem.make(intermediate, 1).getObject());
	}

	@Test
	public void preprintingTraversesIntermediateSpecimensWithoutPrintingTheirLabels() {
		configure(1L, Map.of());
		markIntermediate(1L, "21");
		Specimen intermediate = child(1L, 100L, plasma);
		intermediate.setId(101L);
		intermediate.setLineage(Specimen.DERIVED);
		intermediate.setSpecimenRequirement(prePrintRequirement());
		Specimen aliquot = child(1L, 101L, plasma);
		aliquot.setId(102L);
		aliquot.setLineage(Specimen.ALIQUOT);
		aliquot.setSpecimenRequirement(prePrintRequirement());
		intermediate.getChildCollection().add(aliquot);
		assertFalse(intermediate.isPrePrintEnabled());
		assertTrue(aliquot.isPrePrintEnabled());
		assertEquals(List.of(aliquot), intermediate.getPrePrintItems().stream().map(PrintItem::getObject).toList());
	}

	@Test
	public void apiKeepsRealLabelsAndReturnsReadOnlyPresentationMetadata() throws Exception {
		configure(1L, Map.of());
		markIntermediate(1L, "21");
		Specimen specimen = child(1L, 100L, plasma);
		specimen.setLineage(Specimen.DERIVED);
		specimen.setLabel("PROJECT-SPECIFIC-9001");
		SpecimenInfo info = SpecimenInfo.from(specimen);
		assertEquals("PROJECT-SPECIFIC-9001", info.getLabel());
		assertTrue(info.isIntermediate());
		assertEquals("NRC-0001.1.1", info.getIntermediateSourceLabel());
		assertEquals("NONE", info.getLabelAutoPrintMode());
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(info);
		assertEquals("PROJECT-SPECIFIC-9001", mapper.readTree(json).get("label").asText());
		SpecimenInfo forged = mapper.readValue("{\"label\":\"X\",\"intermediate\":true,\"parentIntermediate\":true,\"intermediateSourceLabel\":\"Y\"}", SpecimenInfo.class);
		assertFalse(forged.isIntermediate());
		assertFalse(forged.isParentIntermediate());
		assertNull(forged.getIntermediateSourceLabel());
		markIntermediate(1L);
		assertFalse(SpecimenInfo.from(specimen).isIntermediate());
		assertEquals("PROJECT-SPECIFIC-9001", specimen.getLabel());
	}

	@Test
	public void referencesSkipIntermediateAncestorsWithoutChangingTheBiologicalParent() {
		configure(1L, Map.of());
		markIntermediate(1L, "21");
		Specimen first = child(1L, 100L, plasma);
		first.setId(101L);
		first.setLineage(Specimen.DERIVED);
		first.setLabel("INTERNAL-1");
		Specimen second = child(1L, 101L, plasma);
		second.setId(102L);
		second.setLineage(Specimen.DERIVED);
		second.setParentSpecimen(first);
		second.setLabel("INTERNAL-2");
		assertEquals("NRC-0001.1.1", SpecimenInfo.from(second).getIntermediateSourceLabel());
		Specimen aliquot = child(1L, 102L, plasma);
		aliquot.setLineage(Specimen.ALIQUOT);
		aliquot.setParentSpecimen(second);
		SpecimenInfo info = SpecimenInfo.from(aliquot);
		assertFalse(info.isIntermediate());
		assertTrue(info.isParentIntermediate());
		assertEquals("Plasma", info.getParentType());
		assertEquals("NRC-0001.1.1", info.getParentIntermediateSourceLabel());
		assertEquals("INTERNAL-2", info.getParentLabel());
		assertEquals(Long.valueOf(102L), info.getParentId());
		assertSame(second, aliquot.getParentSpecimen());
	}

	@Test
	public void intermediateSelectionIsValidatedDeduplicatedAndPreservedInWorkflowJson() {
		Map<String, Object> data = SpecimenTypeAbbreviations.validate(Map.of(
			"abbreviations", Map.of(), "intermediateTypeIds", List.of("21", "21", "22")), pvDao());
		assertEquals(List.of("21", "22"), data.get("intermediateTypeIds"));
		configure(1L, Map.of());
		workflows.get(1L).getWorkflows().get(SpecimenTypeAbbreviations.WORKFLOW).setData(data);
		CpWorkflowConfig restored = new CpWorkflowConfig();
		restored.setWorkflowsJson(workflows.get(1L).getWorkflowsJson());
		assertEquals(List.of("21", "22"), restored.getWorkflows().get(SpecimenTypeAbbreviations.WORKFLOW)
			.getData().get("intermediateTypeIds"));
	}

	@Test
	public void rejectsInvalidIntermediateTypeSelections() {
		for (Object invalid : List.of("21", List.of("99"), List.of("-1"), List.of(21), List.of("999999999999999999999"))) {
			assertThrows(OpenSpecimenException.class, () -> SpecimenTypeAbbreviations.validate(
				Map.of("abbreviations", Map.of(), "intermediateTypeIds", invalid), pvDao()));
		}
		plasma.setAttribute("gender");
		assertThrows(OpenSpecimenException.class, () -> SpecimenTypeAbbreviations.validate(
			Map.of("abbreviations", Map.of(), "intermediateTypeIds", List.of("21")), pvDao()));
	}

	private void markIntermediate(Long cpId, String... ids) {
		CpWorkflowConfig.Workflow workflow = workflows.get(cpId).getWorkflows().get(SpecimenTypeAbbreviations.WORKFLOW);
		Map<String, Object> data = new HashMap<>(workflow.getData());
		data.put("intermediateTypeIds", List.of(ids));
		workflow.setData(data);
	}

	private SpecimenRequirement prePrintRequirement() {
		SpecimenRequirement requirement = new SpecimenRequirement();
		requirement.setLabelAutoPrintMode(CollectionProtocol.SpecimenLabelAutoPrintMode.PRE_PRINT);
		requirement.setLabelPrintCopies(1);
		return requirement;
	}

	private String label(Specimen specimen) {
		return labels.generateLabel("%PSPEC_LABEL%.%SP_TYPE%%SP_TYPE_ABBR_UID(0, output_one, parent_specimen)%", specimen);
	}

	private void configure(Long cpId, Map<String, String> abbreviations) {
		CpWorkflowConfig config = new CpWorkflowConfig();
		CpWorkflowConfig.Workflow workflow = new CpWorkflowConfig.Workflow();
		workflow.setName(SpecimenTypeAbbreviations.WORKFLOW);
		workflow.setData(Map.of("version", 1, "abbreviations", abbreviations));
		config.getWorkflows().put(workflow.getName(), workflow);
		workflows.put(cpId, config);
	}

	private Specimen child(Long cpId, Long parentId, PermissibleValue type) {
		CollectionProtocol cp = new CollectionProtocol();
		cp.setId(cpId);
		CollectionProtocolRegistration registration = new CollectionProtocolRegistration();
		registration.setId(cpId + 10);
		registration.setPpid("NRC-0001");
		registration.setCollectionProtocol(cp);
		Visit visit = new Visit();
		visit.setId(cpId + 20);
		visit.setRegistration(registration);
		visit.setName("NRC-0001.1");
		Specimen parent = new Specimen();
		parent.setId(parentId);
		parent.setLabel("NRC-0001.1.1");
		parent.setCollectionProtocol(cp);
		parent.setVisit(visit);
		Specimen specimen = new Specimen();
		specimen.setCollectionProtocol(cp);
		specimen.setVisit(visit);
		specimen.setParentSpecimen(parent);
		specimen.setSpecimenType(type);
		return specimen;
	}

	private PermissibleValue type(Long id, String value, String abbreviation) {
		PermissibleValue pv = new PermissibleValue();
		pv.setId(id);
		pv.setValue(value);
		pv.setAttribute("specimen_type");
		pv.setParent(new PermissibleValue());
		pv.getProps().put("abbreviation", abbreviation);
		return pv;
	}

	private PermissibleValueDao pvDao() {
		return proxy(PermissibleValueDao.class, (method, args) -> {
			assertEquals("getById", method);
			return Long.valueOf(21).equals(args[0]) ? plasma : Long.valueOf(22).equals(args[0]) ? serum : null;
		});
	}

	private interface Invocation {
		Object invoke(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(Class<T> type, Invocation invocation) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
			(obj, method, args) -> invocation.invoke(method.getName(), args));
	}
}
