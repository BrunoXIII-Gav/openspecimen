package com.krishagni.catissueplus.core.audit.events;

import java.util.Date;
import java.util.Locale;

import com.krishagni.catissueplus.core.common.events.UserSummary;

public record AuditRecordDetail(
	Long id,
	Long revisionId,
	Date changedOn,
	UserSummary changedBy,
	String ipAddress,
	String operation,
	String recordType,
	Long recordId,
	String changeLog
) {
	public static AuditRecordDetail from(RevisionDetail revision, RevisionEntityRecordDetail record) {
		String operation = switch (record.getType()) {
			case 0 -> "insert";
			case 1 -> "update";
			case 2 -> "delete";
			default -> "unknown";
		};
		return new AuditRecordDetail(record.getId(), revision.getRevisionId(), revision.getChangedOn(),
			revision.getChangedBy(), revision.getIpAddress(), operation,
			record.getEntityName(), record.getEntityId(), record.getModifiedProps());
	}

	public static AuditRecordDetail from(FormDataRevisionDetail revision) {
		return new AuditRecordDetail(revision.getId(), revision.getId(), revision.getTime(), revision.getUser(),
			revision.getIpAddress(), revision.getOp().toLowerCase(Locale.ROOT), revision.getFormName(),
			revision.getRecordId(), revision.getData());
	}
}
