package com.krishagni.catissueplus.core.biospecimen.domain;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.biospecimen.events.ConsentStatusDetail;
import com.krishagni.catissueplus.core.biospecimen.events.RegistrationQueryCriteria;
import com.krishagni.catissueplus.core.biospecimen.repository.CollectionProtocolRegistrationDao;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.services.impl.CollectionProtocolRegistrationServiceImpl;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.TransactionalInterceptor;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.util.AuthUtil;
import com.krishagni.rbac.common.errors.RbacErrorCode;
import com.krishagni.rbac.repository.SubjectDao;

import static org.junit.Assert.*;

public class ConsentStatusAccessTest {
  @Test
  public void statusReadRequiresConsentPermissionBeforeQueryingItsFlags() throws Exception {
    TransactionalInterceptor transactions = null;
    Field templateField = TransactionalInterceptor.class.getDeclaredField("txTmpl"); templateField.setAccessible(true);
    Object previousTemplate = null;
    try {
      transactions = (TransactionalInterceptor) TransactionalInterceptor.class.getMethod("aspectOf").invoke(null);
      previousTemplate = templateField.get(transactions);
      transactions.setTransactionManager(new NoDatabaseTransactions());
    } catch (NoSuchMethodException notWoven) { /* ordinary unit-test compilation */ }
    CollectionProtocol cp = new CollectionProtocol(); cp.setId(1L);
    CollectionProtocolRegistration cpr = new CollectionProtocolRegistration();
    cpr.setId(2L); cpr.setCollectionProtocol(cp);
    AtomicInteger reads = new AtomicInteger();
    CollectionProtocolRegistrationDao registrations = proxy(CollectionProtocolRegistrationDao.class, (name, args) -> {
      if (name.equals("getById")) return cpr;
      if (name.equals("getConsentStatus")) { reads.incrementAndGet(); return new ConsentStatusDetail(true, true, false); }
      throw new AssertionError(name);
    });
    DaoFactory biospecimen = proxy(DaoFactory.class, (name, args) -> registrations);
    SubjectDao subjects = proxy(SubjectDao.class, (name, args) -> Collections.emptyList());
    var permissions = proxy(com.krishagni.rbac.repository.DaoFactory.class, (name, args) -> subjects);
    Field singleton = AccessCtrlMgr.class.getDeclaredField("instance"); singleton.setAccessible(true);
    Object previous = singleton.get(null);
    AccessCtrlMgr manager = AccessCtrlMgr.getInstance();
    Field rbacField = AccessCtrlMgr.class.getDeclaredField("daoFactory"); rbacField.setAccessible(true);
    Field bsField = AccessCtrlMgr.class.getDeclaredField("bsDaoFactory"); bsField.setAccessible(true);
    Object previousRbac = rbacField.get(manager), previousBs = bsField.get(manager);
    try {
      rbacField.set(manager, permissions); bsField.set(manager, biospecimen);
      CollectionProtocolRegistrationServiceImpl service = new CollectionProtocolRegistrationServiceImpl();
      service.setDaoFactory(biospecimen);
      RegistrationQueryCriteria criteria = new RegistrationQueryCriteria(); criteria.setCprId(2L);
      User user = new User(); user.setId(3L); user.setType(User.Type.NONE);
      AuthUtil.setCurrentUser(user);
      var denied = service.getConsentStatus(new RequestEvent<>(criteria));
      assertFalse(denied.isSuccessful());
      assertTrue(denied.getError().containsError(RbacErrorCode.ACCESS_DENIED));
      assertEquals(0, reads.get());
      user.setType(User.Type.SUPER);
      var allowed = service.getConsentStatus(new RequestEvent<>(criteria));
      allowed.throwErrorIfUnsuccessful();
      assertEquals("DOCUMENT_PENDING", allowed.getPayload().getStatus());
      assertEquals(1, reads.get());
    } finally {
      AuthUtil.clearCurrentUser();
      rbacField.set(manager, previousRbac); bsField.set(manager, previousBs);
      singleton.set(null, previous);
      if (transactions != null) templateField.set(transactions, previousTemplate);
    }
  }

  private interface Call { Object invoke(String name, Object[] arguments); }

  private static class NoDatabaseTransactions extends AbstractPlatformTransactionManager {
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
    @Override protected void doCommit(DefaultTransactionStatus status) { }
    @Override protected void doRollback(DefaultTransactionStatus status) { }
  }

  @SuppressWarnings("unchecked")
  private <T> T proxy(Class<T> type, Call call) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
      (instance, method, arguments) -> call.invoke(method.getName(), arguments));
  }
}
