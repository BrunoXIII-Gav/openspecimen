const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');

const modulePromise = import('data:text/javascript;base64,' + Buffer.from(
  readFileSync(resolve(__dirname, '../src/audit/report.js'), 'utf8')).toString('base64'));
const now = new Date('2026-10-16T12:00:00');
const defaults = {reportType: 'data', startDate: '2026-10-01T00:00:00', endDate: '2026-10-15T00:00:00'};
const cp = 'com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol';

async function build(overrides = {}, maxPeriod = 90) {
  return (await modulePromise).buildReportCriteria({...defaults, ...overrides}, maxPeriod, now);
}

test('exports detailed history of the selected protocol and user with unique IDs', async () => {
  const {criteria, error} = await build({entity: cp, recordIds: '11, 12,11', user: {id: 2}});
  assert.equal(error, undefined);
  assert.equal(criteria.recordType, 'core');
  assert.deepEqual(criteria.records, [cp]);
  assert.deepEqual(criteria.recordIds, [11, 12]);
  assert.deepEqual(criteria.userIds, [2]);
  assert.equal(criteria.includeModifiedProps, true);
  assert.equal(criteria.useProvidedInstant, true);
  assert.equal(new Date(criteria.startDate).getHours(), 0);
  assert.equal(new Date(criteria.endDate).getHours(), 23);
  assert.equal(new Date(criteria.endDate).getMilliseconds(), 999);
});

test('all data includes forms and does not impose a core-only record filter', async () => {
  const {criteria} = await build();
  assert.deepEqual(criteria.reportTypes, ['data']);
  assert.equal(criteria.recordType, undefined);
  assert.equal(criteria.records, undefined);
  assert.equal(criteria.recordIds, undefined);
  assert.equal(criteria.includeModifiedProps, true);
});

test('rejects IDs without a record type instead of exporting every record with those IDs', async () => {
  assert.equal((await build({recordIds: '11'})).error, 'ids_need_entity');
});

test('rejects malformed, nonpositive and unsafe IDs instead of silently widening the export', async () => {
  for (const recordIds of ['11,', '11,,12', '-1', '0', '1.5', '1e2', 'abc', '9007199254740992']) {
    assert.equal((await build({entity: cp, recordIds})).error, 'invalid_ids', recordIds);
  }
});

test('rejects missing, invalid, reversed and future dates', async () => {
  assert.equal((await build({startDate: ''})).error, 'dates_required');
  assert.equal((await build({endDate: 'bad-date'})).error, 'dates_required');
  assert.equal((await build({startDate: '2026-10-16T00:00:00'})).error, 'dates_order');
  assert.equal((await build({endDate: '2026-10-17T00:00:00'})).error, 'future_dates');
});

test('today ends at the current instant so the server does not receive future timestamps', async () => {
  const {criteria} = await build({endDate: '2026-10-16T00:00:00'});
  assert.equal(criteria.endDate, +now);
});

test('enforces the configured interval and permits longer history for explicit core IDs', async () => {
  assert.equal((await build({}, 5)).error, 'period_too_long');
  const {criteria, error} = await build({entity: cp, recordIds: '11'}, 5);
  assert.equal(error, undefined);
  assert.deepEqual(criteria.recordIds, [11]);
});

test('ignores hidden data filters when exporting other report categories', async () => {
  for (const reportType of ['query_exim', 'auth', 'api_calls']) {
    const {criteria} = await build({reportType, entity: cp, recordIds: '11'});
    assert.deepEqual(criteria.reportTypes, [reportType]);
    assert.equal(criteria.recordType, undefined);
    assert.equal(criteria.records, undefined);
    assert.equal(criteria.recordIds, undefined);
    assert.equal(criteria.includeModifiedProps, undefined);
  }
});

test('requires a supported report category', async () => {
  assert.equal((await build({reportType: null})).error, 'report_type_required');
  assert.equal((await build({reportType: 'unknown'})).error, 'report_type_required');
});

test('an explicit core scope restricts both screen and download to core records', async () => {
  const {criteria} = await build({scope: 'core'});
  assert.equal(criteria.recordType, 'core');
  assert.equal(criteria.records, undefined);
});

test('form filters use the selected form name rather than a hidden core entity', async () => {
  const {criteria} = await build({scope: 'form_data', entity: cp, formName: 'ClinicalData', recordIds: '42'});
  assert.equal(criteria.recordType, 'form_data');
  assert.deepEqual(criteria.records, ['ClinicalData']);
  assert.deepEqual(criteria.recordIds, [42]);
  assert.equal((await build({scope: 'form_data', entity: cp, formName: '', recordIds: '42'})).error, 'ids_need_entity');
});
