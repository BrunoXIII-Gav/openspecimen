const REPORT_TYPES = ['data', 'query_exim', 'auth', 'api_calls'];

export function buildReportCriteria(form, maxPeriod = 90, now = new Date()) {
  if (!REPORT_TYPES.includes(form.reportType)) {
    return {error: 'report_type_required'};
  }

  const start = form.startDate ? new Date(form.startDate) : null;
  const end = form.endDate ? new Date(form.endDate) : null;
  if (!start || !end || !Number.isFinite(+start) || !Number.isFinite(+end)) {
    return {error: 'dates_required'};
  }

  start.setHours(0, 0, 0, 0);
  end.setHours(23, 59, 59, 999);
  const today = new Date(now);
  today.setHours(23, 59, 59, 999);
  if (start > end) return {error: 'dates_order'};
  if (end > today) return {error: 'future_dates'};

  const criteria = {
    reportTypes: [form.reportType],
    startDate: +start,
    // Today's history ends now, avoiding a future timestamp in the server's timezone.
    endDate: Math.min(+end, +now),
    useProvidedInstant: true
  };

  if (form.user?.id) criteria.userIds = [form.user.id];

  if (form.reportType === 'data') {
    criteria.includeModifiedProps = true;
    const scope = form.scope || 'core';
    if (!['core', 'form', 'form_data'].includes(scope)) return {error: 'report_type_required'};
    const selectedRecord = scope === 'core' ? form.entity : (form.formName || '').trim();
    const idsText = (form.recordIds || '').trim();
    if (idsText && !selectedRecord) return {error: 'ids_need_entity'};
    if (idsText && !/^\d+(\s*,\s*\d+)*$/.test(idsText)) return {error: 'invalid_ids'};
    if (idsText) {
      const ids = idsText.split(',').map(id => Number(id.trim()));
      if (ids.some(id => !Number.isSafeInteger(id) || id <= 0)) return {error: 'invalid_ids'};
      criteria.recordIds = [...new Set(ids)];
    }

    if (selectedRecord || form.scope) {
      criteria.recordType = scope;
      if (selectedRecord) criteria.records = [selectedRecord];
    }
  }

  // The server permits longer intervals when a core record's IDs are specified.
  if (!criteria.recordIds && Math.floor((end - start) / 86400000) > maxPeriod) {
    return {error: 'period_too_long'};
  }

  return {criteria};
}
