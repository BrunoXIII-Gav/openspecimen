const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

// Opt in explicitly. Creates and drops only its own isolated fixture database.
test('live consent tracking SQL handles decisions, groups, policies and source protocols', {
  skip: process.env.OS_CONSENT_DB_TEST !== 'true'
}, () => {
  const database = 'os_consent_tracking_test_' + Date.now();
  function sql(statement, selected = database) {
    return execFileSync('docker', ['exec', '-i', 'os-db', 'sh', '-c',
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --default-character-set=utf8mb4 "$@"',
      'mysql', ...(selected ? [selected] : [])], {input: statement, encoding: 'utf8'}).trim();
  }
  const status = (id = 1) => sql(`select status from OS_CPR_CONSENT_STATUS_V where cpr_id = ${id}`);
  const workflows = value => sql("update OS_CP_WORKFLOWS set workflows = '" + JSON.stringify(value).replaceAll("'", "''") + "' where cp_id = 1");
  sql(`create database ${database} character set utf8mb4`, '');
  try {
    sql(`
      create table CATISSUE_COLLECTION_PROTOCOL (identifier bigint primary key, consents_source_cp_id bigint,
        consents_waived int, visit_consents int, activity_status varchar(20));
      create table CATISSUE_COLL_PROT_REG (identifier bigint primary key, collection_protocol_id bigint,
        participant_id bigint, consent_document_name varchar(255), activity_status varchar(20));
      create table OS_CP_WORKFLOWS (cp_id bigint primary key, workflows json);
      create table CATISSUE_CONSENT_TIER (identifier bigint primary key, coll_protocol_id bigint,
        consent_stmt_id bigint, activity_status varchar(20));
      create table OS_CONSENT_STATEMENTS (identifier bigint primary key, code varchar(255));
      create table CATISSUE_CONSENT_TIER_RESPONSE (identifier bigint primary key, consent_tier_id bigint,
        coll_prot_reg_id bigint, response_id bigint);
      create table CATISSUE_PERMISSIBLE_VALUE (identifier bigint primary key, value varchar(50));
      insert into CATISSUE_COLLECTION_PROTOCOL values (1,null,0,0,'Active'),(2,1,0,0,'Active');
      insert into CATISSUE_COLL_PROT_REG values (1,1,10,null,'Active'),(2,2,10,null,'Active'),(3,2,11,null,'Active');
      insert into OS_CP_WORKFLOWS values (1,'[]');
      insert into OS_CONSENT_STATEMENTS values (1,'CI001'),(2,'CI002'),(3,'CI003');
      insert into CATISSUE_CONSENT_TIER values (1,1,1,'Active'),(2,1,2,'Active'),(3,1,3,'Disabled');
      insert into CATISSUE_PERMISSIBLE_VALUE values (1,'Yes'),(2,'No'),(3,'Not Specified'),(4,'Withdrawn'),(5,'None');
    `);
    const xml = fs.readFileSync(path.join(__dirname, '../../WEB-INF/resources/db/13.0/consent-tracking.xml'), 'utf8');
    for (const match of xml.matchAll(/<changeSet\b([^>]*)>([\s\S]*?)<\/changeSet>/g)) {
      if (match[1].includes('dbms="oracle"')) continue;
      sql(match[2].match(/<!\[CDATA\[([\s\S]*?)\]\]>/)[1]);
    }
    assert.equal(status(), 'BOTH_PENDING');
    sql("update CATISSUE_COLL_PROT_REG set consent_document_name = 'signed.pdf' where identifier = 1");
    assert.equal(status(), 'RESPONSES_PENDING');
    sql('insert into CATISSUE_CONSENT_TIER_RESPONSE values (1,1,1,2),(2,2,1,1)');
    assert.equal(status(), 'NO_PENDING', 'explicit No is a recorded decision');
    assert.equal(status(2), 'NO_PENDING', 'source protocol document and responses are reused');
    assert.equal(status(3), 'BOTH_PENDING', 'absent source registration is pending');
    sql('update CATISSUE_CONSENT_TIER_RESPONSE set response_id = 3 where identifier = 1');
    assert.equal(status(), 'RESPONSES_PENDING');
    sql('update CATISSUE_CONSENT_TIER_RESPONSE set response_id = 5 where identifier = 1');
    assert.equal(status(), 'RESPONSES_PENDING', 'None is not a decision');
    sql('update CATISSUE_CONSENT_TIER_RESPONSE set response_id = 4 where identifier = 1');
    assert.equal(status(), 'NO_PENDING', 'withdrawal is recorded, not interpreted as missing');
    sql('delete from CATISSUE_CONSENT_TIER_RESPONSE where identifier = 1');
    workflows([{name:'consentGroups',data:{groups:[{members:[{statementCode:'CI001'},{statementCode:'CI002'}]}]}}]);
    assert.equal(status(), 'NO_PENDING', 'one answered alternative completes the default group');
    workflows([{name:'consentGroups',data:{groups:[{completionMode:'ALL',members:[{statementCode:'CI001'},{statementCode:'CI002'}]}]}}]);
    assert.equal(status(), 'RESPONSES_PENDING', 'ALL groups require each applicable response');
    workflows([{name:'consentGroups',data:{groups:[{completionMode:'ALL',members:[
      {statementCode:'CI001',disabledWhen:['CI002']},{statementCode:'CI002'}]}]}}]);
    assert.equal(status(), 'NO_PENDING', 'disabled declarations are not required');
    workflows([{name:'consentGroups',data:{groups:[{completionMode:'ALL',members:[
      {statementCode:'CI001',visibleWhen:['CI002']},{statementCode:'CI002'}]}]}}]);
    assert.equal(status(), 'RESPONSES_PENDING', 'visible declaration still requires a decision');
    sql('update CATISSUE_CONSENT_TIER_RESPONSE set response_id = 2 where identifier = 2');
    assert.equal(status(), 'NO_PENDING', 'hidden declaration is not required');
    sql('delete from CATISSUE_CONSENT_TIER_RESPONSE');
    workflows([{name:'consentGroups',data:{groups:[{required:false,members:[{statementCode:'CI001'},{statementCode:'CI002'}]}]}}]);
    assert.equal(status(), 'NO_PENDING', 'optional group is excluded');
    workflows([{name:'consentTracking',data:{documentRequired:false,responsesRequired:false}}]);
    sql('update CATISSUE_COLL_PROT_REG set consent_document_name = null where identifier = 1');
    assert.equal(status(), 'NO_PENDING');
    workflows([{name:'consentTracking',data:{documentRequired:true,responsesRequired:false}}]);
    assert.equal(status(), 'DOCUMENT_PENDING');
    workflows([]);
    sql('update CATISSUE_COLLECTION_PROTOCOL set consents_waived = 1 where identifier = 1');
    assert.equal(status(), 'NOT_APPLICABLE');
    sql('update CATISSUE_COLLECTION_PROTOCOL set consents_waived = 0, visit_consents = 1 where identifier = 1');
    assert.equal(status(), 'NOT_APPLICABLE', 'visit-level consents do not use participant tracking');
    sql('update CATISSUE_COLLECTION_PROTOCOL set visit_consents = 0 where identifier = 1');
    sql("update CATISSUE_CONSENT_TIER set activity_status = 'Disabled'");
    assert.equal(status(), 'DOCUMENT_PENDING', 'no declarations means no missing responses');
    sql("update CATISSUE_COLL_PROT_REG set consent_document_name = 'signed.pdf' where identifier = 1");
    assert.equal(status(), 'NO_PENDING');
    sql("update CATISSUE_COLL_PROT_REG set consent_document_name = '   ' where identifier = 1");
    assert.equal(status(), 'DOCUMENT_PENDING');
    sql("update CATISSUE_COLL_PROT_REG set activity_status = 'Disabled' where identifier = 1");
    assert.equal(status(), '', 'deleted participants are not reported');
  } finally {
    sql(`drop database ${database}`, '');
  }
});
