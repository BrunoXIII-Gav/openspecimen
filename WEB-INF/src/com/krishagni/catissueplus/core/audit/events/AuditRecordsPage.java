package com.krishagni.catissueplus.core.audit.events;

import java.util.List;

public record AuditRecordsPage(List<AuditRecordDetail> records, boolean hasMore, Long nextCursor) {
	public static AuditRecordsPage from(List<AuditRecordDetail> records, int pageSize) {
		boolean hasMore = records.size() > pageSize;
		List<AuditRecordDetail> page = List.copyOf(records.subList(0, Math.min(records.size(), pageSize)));
		Long cursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).id() : null;
		return new AuditRecordsPage(page, hasMore, cursor);
	}
}
