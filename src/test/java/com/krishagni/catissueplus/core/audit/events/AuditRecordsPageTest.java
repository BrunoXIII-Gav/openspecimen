package com.krishagni.catissueplus.core.audit.events;

import java.util.List;

import org.junit.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.Assert.*;

public class AuditRecordsPageTest {
	@Test
	public void lookAheadDoesNotSkipTheFirstRecordOfTheNextPage() {
		AuditRecordsPage page = AuditRecordsPage.from(List.of(row(30), row(29), row(28)), 2);
		assertTrue(page.hasMore());
		assertEquals(Long.valueOf(29), page.nextCursor());
		assertEquals(List.of(30L, 29L), page.records().stream().map(AuditRecordDetail::id).toList());
	}

	@Test
	public void lastAndEmptyPagesHaveNoNextCursor() {
		assertFalse(AuditRecordsPage.from(List.of(row(1)), 2).hasMore());
		assertNull(AuditRecordsPage.from(List.of(row(1)), 2).nextCursor());
		assertEquals(List.of(), AuditRecordsPage.from(List.of(), 2).records());
	}

	@Test
	public void recordsWithinTheSameRevisionRemainIndependentlyPageable() {
		AuditRecordsPage first = AuditRecordsPage.from(List.of(row(30), row(29)), 1);
		AuditRecordsPage second = AuditRecordsPage.from(List.of(row(29)), 1);
		assertEquals(first.records().get(0).revisionId(), second.records().get(0).revisionId());
		assertNotEquals(first.records().get(0).id(), second.records().get(0).id());
	}

	@Test
	public void serializesCursorAndChangesForTheBrowser() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(AuditRecordsPage.from(List.of(row(30), row(29)), 1));
		assertEquals(30L, mapper.readTree(json).get("nextCursor").asLong());
		assertTrue(mapper.readTree(json).get("hasMore").asBoolean());
		assertEquals("enrollment=2000", mapper.readTree(json).get("records").get(0).get("changeLog").asText());
	}

	private AuditRecordDetail row(long id) {
		return new AuditRecordDetail(id, 7L, null, null, null, "update", "CollectionProtocol", 11L, "enrollment=2000");
	}
}
