package com.krishagni.catissueplus.core.biospecimen.domain;

import java.math.BigDecimal;

import org.junit.Test;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SpecimenParentQuantityTest {
	@Test
	public void derivativeDebitsOnlyProcessedParentQuantity() {
		Specimen parent = parent("10", "10");
		Specimen derivative = child(parent, Specimen.DERIVED, "4");
		derivative.setInitialQuantity(new BigDecimal("7"));

		derivative.decChildQtyFromParent();

		assertEquals(0, new BigDecimal("6").compareTo(parent.getAvailableQuantity()));
	}

	@Test
	public void aliquotDebitsItsInitialQuantity() {
		Specimen parent = parent("10", "10");
		Specimen aliquot = child(parent, Specimen.ALIQUOT, null);
		aliquot.setInitialQuantity(new BigDecimal("3"));

		aliquot.decChildQtyFromParent();

		assertEquals(0, new BigDecimal("7").compareTo(parent.getAvailableQuantity()));
	}

	@Test
	public void rejectsParentWithoutKnownQuantity() {
		Specimen parent = parent(null, null);
		Specimen derivative = child(parent, Specimen.DERIVED, "2");

		assertThrows(OpenSpecimenException.class, derivative::decChildQtyFromParent);
	}

	@Test
	public void rejectsOverdrawWithoutClampingBalance() {
		Specimen parent = parent("5", "1");
		Specimen derivative = child(parent, Specimen.DERIVED, "2");

		assertThrows(OpenSpecimenException.class, derivative::decChildQtyFromParent);
		assertEquals(0, BigDecimal.ONE.compareTo(parent.getAvailableQuantity()));
	}

	private Specimen parent(String initial, String available) {
		Specimen parent = new Specimen();
		if (initial != null) parent.setInitialQuantity(new BigDecimal(initial));
		if (available != null) parent.setAvailableQuantity(new BigDecimal(available));
		return parent;
	}

	private Specimen child(Specimen parent, String lineage, String consumed) {
		Specimen child = new Specimen();
		child.setParentSpecimen(parent);
		child.setLineage(lineage);
		child.setCollectionStatus(Specimen.COLLECTED);
		if (consumed != null) child.setParentConsumedQuantity(new BigDecimal(consumed));
		return child;
	}
}
