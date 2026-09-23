package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;

public class FieldReferenceRulesTest {
	@Test
	public void acceptsSupportedFixedField() {
		validate(Map.of("target", "visit", "source", "participant", "field", "ppid", "caption", "PPID"));
	}

	@Test
	public void assignsVersionAndStableRuleId() {
		Map<String, Object> rule = new HashMap<>(Map.of(
			"target", "visit", "source", "participant", "field", "ppid", "caption", "PPID"));
		Map<String, Object> data = new HashMap<>();
		data.put("rules", new ArrayList<>(List.of(rule)));
		FieldReferenceRules.validate(1L, data, null, null);
		assertEquals(1, data.get("version"));
		assertTrue(rule.get("id") instanceof String);
	}

	@Test(expected = OpenSpecimenException.class)
	public void rejectsUnsupportedConfigurationVersion() {
		Map<String, Object> data = new HashMap<>();
		data.put("version", 2);
		data.put("rules", List.of());
		FieldReferenceRules.validate(1L, data, null, null);
	}

	@Test(expected = OpenSpecimenException.class)
	public void rejectsArbitraryBeanPath() {
		validate(Map.of("target", "visit", "source", "participant", "field", "participant.password", "caption", "Secret"));
	}

	@Test(expected = OpenSpecimenException.class)
	public void rejectsNonAncestorSource() {
		validate(Map.of("target", "visit", "source", "specimen", "field", "label", "caption", "Label"));
	}

	@Test(expected = OpenSpecimenException.class)
	public void rejectsUnknownRecordPolicy() {
		validate(Map.of(
			"target", "visit", "source", "participant", "field", "ppid", "caption", "PPID",
			"recordPolicy", "allUnrestricted"));
	}

	@Test(expected = OpenSpecimenException.class)
	public void rejectsTooManyRules() {
		FieldReferenceRules.validate(1L, Map.of("rules", java.util.Collections.nCopies(201, Map.of())), null, null);
	}

	@Test
	public void excludesBinaryAndRepeatingControls() {
		assertFalse(FieldReferenceRules.isReferenceableControlType("fileUpload"));
		assertFalse(FieldReferenceRules.isReferenceableControlType("signature"));
		assertFalse(FieldReferenceRules.isReferenceableControlType("subForm"));
		assertTrue(FieldReferenceRules.isReferenceableControlType("textField"));
	}

	@Test
	public void acceptsPlacementAfterFixedDestinationField() {
		validate(Map.of(
			"target", "visit", "source", "participant", "field", "ppid", "caption", "PPID",
			"afterField", "visitDate"));
	}

	@Test(expected = OpenSpecimenException.class)
	public void rejectsPlacementOutsideDestination() {
		validate(Map.of(
			"target", "visit", "source", "participant", "field", "ppid", "caption", "PPID",
			"afterField", "participant.emailAddress"));
	}

	private void validate(Map<String, Object> rule) {
		Map<String, Object> data = new HashMap<>();
		data.put("rules", new ArrayList<>(List.of(new HashMap<>(rule))));
		FieldReferenceRules.validate(1L, data, null, null);
	}
}
