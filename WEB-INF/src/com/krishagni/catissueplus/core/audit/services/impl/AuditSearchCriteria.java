package com.krishagni.catissueplus.core.audit.services.impl;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.krishagni.catissueplus.core.audit.domain.factory.AuditErrorCode;
import com.krishagni.catissueplus.core.audit.repository.RevisionsListCriteria;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.util.Utility;

final class AuditSearchCriteria {
	private AuditSearchCriteria() { }

	static int validate(RevisionsListCriteria criteria, int maxPeriod, Date now) {
		String type = StringUtils.defaultIfBlank(criteria.recordType(), "core");
		if (!Set.of("core", "form", "form_data").contains(type)
			|| (!type.equals("core") && CollectionUtils.isNotEmpty(criteria.entities()))
			|| (CollectionUtils.isNotEmpty(criteria.reportTypes()) && !criteria.reportTypes().equals(List.of("data")))
			|| (criteria.lastId() != null && criteria.lastId() <= 0)
			|| !validIds(criteria.recordIds()) || !validIds(criteria.userIds())
			|| (criteria.records() != null && (criteria.records().size() > 1000 || criteria.records().stream().anyMatch(StringUtils::isBlank)))
			|| (criteria.entities() != null && (criteria.entities().size() > 1000 || criteria.entities().stream().anyMatch(StringUtils::isBlank)))) {
			throw OpenSpecimenException.userError(AuditErrorCode.INVALID_SEARCH_CRITERIA);
		}

		if (criteria.startDate() == null) throw OpenSpecimenException.userError(AuditErrorCode.START_DATE_REQ);
		if (criteria.endDate() == null) throw OpenSpecimenException.userError(AuditErrorCode.END_DATE_REQ);

		Date start = criteria.useProvidedInstant() ? criteria.startDate() : Utility.chopSeconds(criteria.startDate());
		Date end = criteria.useProvidedInstant() ? criteria.endDate() : Utility.getEndOfDay(criteria.endDate());
		Date today = Utility.getEndOfDay(now);
		if (start.after(today) || end.after(today)) {
			throw OpenSpecimenException.userError(AuditErrorCode.DATE_GT_TODAY, Utility.getDateTimeString(end));
		}
		if (start.after(end)) {
			throw OpenSpecimenException.userError(AuditErrorCode.FROM_DT_GT_TO_DATE,
				Utility.getDateTimeString(start), Utility.getDateTimeString(end));
		}

		boolean selectedRecords = CollectionUtils.isNotEmpty(criteria.records()) || CollectionUtils.isNotEmpty(criteria.entities());
		if (CollectionUtils.isNotEmpty(criteria.recordIds()) && !selectedRecords) {
			throw OpenSpecimenException.userError(AuditErrorCode.RECORDS_REQ);
		}
		if ((CollectionUtils.isEmpty(criteria.recordIds()) || type.equals("form")) && Utility.daysBetween(start, end) > maxPeriod) {
			throw OpenSpecimenException.userError(AuditErrorCode.DATE_INTERVAL_GT_ALLOWED, maxPeriod);
		}

		int pageSize = Math.min(criteria.maxResults(), 50);
		criteria.recordType(type).startDate(start).endDate(end).includeModifiedProps(true).startAt(0).maxResults(pageSize + 1);
		return pageSize;
	}

	private static boolean validIds(List<Long> ids) {
		return ids == null || (ids.size() <= 1000 && ids.stream().allMatch(id -> id != null && id > 0));
	}
}
