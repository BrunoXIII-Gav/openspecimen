package com.krishagni.catissueplus.core.common.access;

import java.util.Set;
import org.junit.Test;
import com.krishagni.catissueplus.core.biospecimen.repository.impl.BiospecimenDaoHelper;
import static org.junit.Assert.*;

public class ProtocolQueryAccessTest {

	@Test
	public void nativeRegistryIncludesNavigationAndExcludesCustomForms() {
		for (String field : Set.of("CollectionProtocol.id", "CollectionProtocol.Title", "CollectionProtocol.catalogId",
			"CollectionProtocol.__userLabels.userId", "CollectionProtocol.cpSites.siteId",
			"CollectionProtocol.principalInvestigator.investigatorName", "Participant.ppid", "Specimen.label",
			"Specimen.spmnCollRecvDetails.recvTime")) {
			assertTrue(field, ProtocolQueryAccess.isNativeField(field));
		}
		assertFalse(ProtocolQueryAccess.isNativeField("Specimen.extensions.Hidden.requiredField"));
		assertFalse(ProtocolQueryAccess.isNativeField("Participant.customFields.privateField"));
	}

	@Test
	public void functionsAndLiteralStringsAreCheckedAsExpressions() {
		assertTrue(nativeQuery("select concat(CollectionProtocol.shortTitle, \"Specimen.extensions.Hidden.secret\") where CollectionProtocol.id exists"));
		assertTrue(nativeQuery("select count(distinct CollectionProtocol.id) where CollectionProtocol.id = 1"));
		assertTrue(nativeQuery("select distinct CollectionProtocol.shortTitle where CollectionProtocol.id exists order by CollectionProtocol.shortTitle desc limit 0, 100"));
	}

	@Test
	public void hiddenFieldsCannotLeakThroughAnyQueryClause() {
		for (String aql : Set.of(
			"select Specimen.extensions.Hidden.secret where Specimen.id exists",
			"select Specimen.id where Specimen.extensions.Hidden.secret exists",
			"select Specimen.id where Specimen.id exists order by Specimen.extensions.Hidden.secret",
			"select count(Specimen.id) where Specimen.id exists having count(Specimen.extensions.Hidden.secret) > 0",
			"select Specimen.id where Specimen.id in (select Specimen.id where Specimen.extensions.Hidden.secret exists)",
			"select Unknown.privateField where Specimen.id exists")) {
			assertFalse(aql, nativeQuery(aql));
		}
	}

	@Test
	public void receiptCannotBeUsedAsAPredicateWhenItIsNotSelected() {
		assertFalse(ProtocolQueryAccess.permits("select Specimen.id where Specimen.spmnCollRecvDetails.recvTime exists",
			name -> ProtocolQueryAccess.isNativeField(name) && !name.contains(".spmnCollRecvDetails.")));
		assertFalse(nativeQuery("this is not AQL"));
	}

	@Test
	public void allCurrentAndFutureSitesUseOnlyTheCanonicalMetadataSql() {
		var helper = BiospecimenDaoHelper.getInstance();
		var assignment = java.util.List.of(SiteCpPair.make(1L, null, 1L));
		assertTrue(nativeQuery("select CollectionProtocol.id where " + helper.getSiteCpsCondAqlForCps(assignment)));
		assertTrue(nativeQuery("select Participant.id where " + helper.getSiteCpsCondAql(assignment, true)));
		assertTrue(nativeQuery("select Specimen.id where " + helper.getSiteCpsCondAql(assignment, false)));
		// Site-specific and mixed assignments retain their existing semantics too.
		assertTrue(nativeQuery("select CollectionProtocol.id where " + helper.getSiteCpsCondAqlForCps(
			java.util.List.of(SiteCpPair.make(1L, 7L, 1L), SiteCpPair.make(2L, null, 2L)))));
	}

	@Test
	public void metadataSqlExceptionDoesNotAllowOtherTablesFieldsOperatorsOrInjectedConditions() {
		String safeSql = "select identifier from catissue_site where institute_id = 1 and activity_status != 'Disabled'";
		for (String sql : Set.of("select identifier from catissue_participant",
			safeSql + " union select identifier from catissue_participant", safeSql + " or 1 = 1",
			safeSql + "; select 1", safeSql.replace("institute_id = 1", "institute_id = 1 or 1=1"))) {
			assertFalse(sql, nativeQuery("select CollectionProtocol.id where CollectionProtocol.cpSites.siteId in sql(\"" + sql + "\")"));
		}
		assertFalse(nativeQuery("select Specimen.id where Specimen.spmnCollRecvDetails.recvQuality in sql(\"" + safeSql + "\")"));
		assertFalse(nativeQuery("select CollectionProtocol.id where CollectionProtocol.cpSites.siteId not in sql(\"" + safeSql + "\")"));
	}

	private boolean nativeQuery(String aql) {
		return ProtocolQueryAccess.permits(aql, ProtocolQueryAccess::isNativeField);
	}
}
