package com.krishagni.catissueplus.core.audit.services.impl;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import com.krishagni.catissueplus.core.audit.domain.factory.AuditErrorCode;
import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.audit.repository.RevisionsListCriteria;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.util.AuthUtil;

import static org.junit.Assert.*;

public class AuditSearchCriteriaTest {
	private final Date now = Date.from(Instant.parse("2026-10-16T12:00:00Z"));

	@Test
	public void enforcesPageBoundAndEnablesChangedValues() {
		RevisionsListCriteria criteria = criteria().maxResults(10000).startAt(90000);
		assertEquals(50, AuditSearchCriteria.validate(criteria, 90, now));
		assertEquals(51, criteria.maxResults());
		assertEquals(0, criteria.startAt());
		assertTrue(criteria.includeModifiedProps());
	}

	@Test
	public void rejectsDatesAndIdsThatWouldBroadenTheSearch() {
		assertError(criteria().startDate(null), AuditErrorCode.START_DATE_REQ);
		assertError(criteria().endDate(null), AuditErrorCode.END_DATE_REQ);
		assertError(criteria().recordIds(List.of(11L)), AuditErrorCode.RECORDS_REQ);
		assertError(criteria().lastId(-1L), AuditErrorCode.INVALID_SEARCH_CRITERIA);
		assertError(criteria().recordIds(List.of(0L)), AuditErrorCode.INVALID_SEARCH_CRITERIA);
	}

	@Test
	public void rejectsUnsupportedScopesAndReportTypes() {
		assertError(criteria().recordType("other"), AuditErrorCode.INVALID_SEARCH_CRITERIA);
		assertError(criteria().reportTypes(List.of("auth")), AuditErrorCode.INVALID_SEARCH_CRITERIA);
	}

	@Test
	public void permitsLongHistoryOnlyForExplicitRecordIds() {
		RevisionsListCriteria criteria = criteria().startDate(Date.from(Instant.parse("2026-01-01T00:00:00Z")));
		assertError(criteria, AuditErrorCode.DATE_INTERVAL_GT_ALLOWED);
		criteria.records(List.of("CollectionProtocol")).recordIds(List.of(11L));
		assertEquals(50, AuditSearchCriteria.validate(criteria, 90, now));
	}

	@Test
	public void cursorSurvivesJsonDeserialization() throws Exception {
		RevisionsListCriteria criteria = new ObjectMapper().readValue("{\"lastId\":123,\"maxResults\":25}", RevisionsListCriteria.class);
		assertEquals(Long.valueOf(123), criteria.lastId());
		assertEquals(25, criteria.maxResults());
	}

	@Test
	public void deniesAnonymousAccessBeforeReadingSettingsOrTheDatabase() {
		AuthUtil.clearCurrentUser();
		assertFalse(new AuditServiceImpl().searchRecords(RequestEvent.wrap(criteria())).isSuccessful());
	}

	@Test
	public void deniesInstituteAdministratorsAsRequiredByTheExportPermissions() {
		User user = new User();
		user.setType(User.Type.INSTITUTE);
		AuthUtil.setCurrentUser(user);
		try {
			assertFalse(new AuditServiceImpl().searchRecords(RequestEvent.wrap(criteria())).isSuccessful());
		} finally {
			AuthUtil.clearCurrentUser();
		}
	}

	private RevisionsListCriteria criteria() {
		return new RevisionsListCriteria().recordType("core").useProvidedInstant(true)
			.startDate(Date.from(Instant.parse("2026-10-01T00:00:00Z")))
			.endDate(Date.from(Instant.parse("2026-10-15T23:59:59Z")));
	}

	private void assertError(RevisionsListCriteria criteria, AuditErrorCode code) {
		OpenSpecimenException error = assertThrows(OpenSpecimenException.class,
			() -> AuditSearchCriteria.validate(criteria, 90, now));
		assertTrue(error.containsError(code));
	}
}
