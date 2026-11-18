package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;
import org.springframework.beans.factory.aspectj.AnnotationBeanConfigurerAspect;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.krishagni.catissueplus.core.administrative.domain.Institute;
import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.repository.CollectionProtocolDao;
import com.krishagni.catissueplus.core.common.TransactionalInterceptor;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.service.impl.ProtocolContentService;
import com.krishagni.catissueplus.core.common.util.AuthUtil;
import com.krishagni.catissueplus.core.de.domain.FormInfoCache;
import com.krishagni.catissueplus.core.de.repository.FormDao;
import com.krishagni.rbac.repository.RoleDao;

import static org.junit.Assert.*;

/** Run against AspectJ output as well as the ordinary unit-test compilation. */
public class ProtocolContentWiringTest {

	@Test
	public void wovenAccessChecksAreInjectedAndServiceTransactionsWork() throws Exception {
		boolean woven;
		try { TransactionalInterceptor.class.getMethod("aspectOf"); woven = true; }
		catch (NoSuchMethodException exception) { woven = false; }
		Assume.assumeTrue("Requires the project's AspectJ compilation", woven);

		Field singleton = ProtocolContentAccess.class.getDeclaredField("instance"); singleton.setAccessible(true);
		Object original = singleton.get(null);
		Field accessSingleton = AccessCtrlMgr.class.getDeclaredField("instance"); accessSingleton.setAccessible(true);
		Object originalObjectAccess = accessSingleton.get(null);
		TransactionalInterceptor transactions = (TransactionalInterceptor) TransactionalInterceptor.class.getMethod("aspectOf").invoke(null);
		NoDatabaseTransactionManager transactionManager = new NoDatabaseTransactionManager();
		transactions.setTransactionManager(transactionManager);
		User user = new User(); user.setId(5L); user.setType(User.Type.SUPER);
		Institute institute = new Institute(); institute.setId(1L); user.setInstitute(institute);
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
			RoleDao roles = proxy(RoleDao.class, (method, args) -> {
				if (method.equals("hasContentPolicies")) return true;
				if (method.equals("getProtocolContentRoles")) return List.of(ProtocolContentAccessTest.role("Specimen", ProtocolContentAccessTest.rule(1L, "collection")));
				throw new AssertionError(method);
			});
			CollectionProtocol cp = new CollectionProtocol(); cp.setId(1L);
			CollectionProtocolDao protocols = proxy(CollectionProtocolDao.class, (method, args) -> cp);
			var rbac = proxy(com.krishagni.rbac.repository.DaoFactory.class, (method, args) -> {
				if (method.equals("getRoleDao")) return roles;
				if (method.equals("getCollectionProtocolDao")) return protocols;
				throw new AssertionError(method);
			});
			context.getBeanFactory().registerSingleton("rbacDaoFactory", rbac);
			var registration = new com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolRegistration();
			registration.setCollectionProtocol(cp); registration.setId(2L);
			var visit = new com.krishagni.catissueplus.core.biospecimen.domain.Visit(); visit.setId(3L); visit.setRegistration(registration);
			var existingSpecimen = new com.krishagni.catissueplus.core.biospecimen.domain.Specimen();
			existingSpecimen.setId(9L); existingSpecimen.setVisit(visit); existingSpecimen.setCollectionProtocol(cp);
			existingSpecimen.setCollectionStatus("Collected");
			var specimens = proxy(com.krishagni.catissueplus.core.biospecimen.repository.SpecimenDao.class, (method, args) -> {
				assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
				return args[0].equals(9L) ? existingSpecimen : null;
			});
			context.getBeanFactory().registerSingleton("biospecimenDaoFactory", proxy(com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory.class, (method, args) -> method.equals("getSpecimenDao") ? specimens : null));
			var form = new com.krishagni.catissueplus.core.de.domain.Form();
			form.setId(90L); form.setName("SharedForm"); form.setCaption("Shared form");
			var specimenForm = new com.krishagni.catissueplus.core.de.events.FormSummary();
			specimenForm.setFormId(90L); specimenForm.setName("SharedForm"); specimenForm.setEntityType("SpecimenEvent");
			var visitForm = new com.krishagni.catissueplus.core.de.events.FormSummary();
			visitForm.setFormId(90L); visitForm.setName("SharedForm"); visitForm.setEntityType("VisitExtension");
			context.getBeanFactory().registerSingleton("formDao", proxy(FormDao.class, (method, args) -> {
				if (method.equals("getForms")) return List.of(form);
				if (method.equals("getEntityForms")) return List.of(specimenForm, visitForm);
				throw new AssertionError(method);
			}));
			context.getBeanFactory().registerSingleton("formInfoCache", new FormInfoCache() {
				@Override public java.util.Map<String, Object> getFormInfo(Long cpId, String type) { return null; }
			});
			context.registerBean(ProtocolContentService.class);
			context.registerBean(com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenWorkflowService.class);
			var processingAttempted = new java.util.concurrent.atomic.AtomicBoolean();
			context.getBeanFactory().registerSingleton("specimenService", proxy(com.krishagni.catissueplus.core.biospecimen.services.SpecimenService.class, (method, args) -> {
				assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); processingAttempted.set(true);
				return com.krishagni.catissueplus.core.common.events.ResponseEvent.userError(com.krishagni.rbac.common.errors.RbacErrorCode.ACCESS_DENIED);
			}));
			context.registerBean(com.krishagni.catissueplus.rest.controller.SpecimenWorkflowController.class);
			context.registerBean(com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenNavigationService.class);
			context.registerBean(com.krishagni.catissueplus.rest.controller.SpecimenNavigationController.class);
			context.refresh();
			AnnotationBeanConfigurerAspect.aspectOf().setBeanFactory(context.getBeanFactory());
			AnnotationBeanConfigurerAspect.aspectOf().afterPropertiesSet();
			singleton.set(null, null); accessSingleton.set(null, null);
			ProtocolContentAccess content = ProtocolContentAccess.getInstance();
			Field injected = ProtocolContentAccess.class.getDeclaredField("rbacDaos"); injected.setAccessible(true);
			assertSame(rbac, injected.get(content));
			AuthUtil.setCurrentUser(user);
			var workflow = context.getBean(com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenWorkflowService.class);
			var navigation = context.getBean(com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenNavigationService.class);
			assertFalse(navigation.getRegistrations(-1L, 0, 50).isSuccessful());
			assertThrows(OpenSpecimenException.class, () -> context.getBean(com.krishagni.catissueplus.rest.controller.SpecimenNavigationController.class).registrations(-1L, 0, 50, null, true, false));
			assertFalse(workflow.getContext(999L).isSuccessful());
			int previousRollbacks = transactionManager.rollbacks;
			assertFalse(workflow.saveMatrix(new com.krishagni.catissueplus.core.biospecimen.events.SpecimenMatrixInput()).isSuccessful());
			assertTrue(transactionManager.rollbacks > previousRollbacks);
			var matrix = new com.krishagni.catissueplus.core.biospecimen.events.SpecimenMatrixInput(); matrix.setCpId(1L);
			var reference = new com.krishagni.catissueplus.core.biospecimen.events.SpecimenDetail(); reference.setId(9L); matrix.setSpecimens(List.of(reference));
			previousRollbacks = transactionManager.rollbacks;
			assertFalse(workflow.saveMatrix(matrix).isSuccessful()); assertTrue(processingAttempted.get());
			assertTrue(transactionManager.rollbacks > previousRollbacks);
			assertThrows(OpenSpecimenException.class, () -> context.getBean(com.krishagni.catissueplus.rest.controller.SpecimenWorkflowController.class).context(999L));
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			ProtocolContentService service = context.getBean(ProtocolContentService.class);
			assertEquals(true, service.getAccess(1L).getPayload().get("unrestricted"));
			var options = service.getOptions(1L).getPayload();
			assertEquals(3, options.size());
			assertEquals(java.util.Set.of("SpecimenEvent", "VisitExtension"), options.get(2).get("entityTypes"));
			user.setType(User.Type.NONE);
			assertEquals(false, service.getAccess(1L).getPayload().get("unrestricted"));
			assertEquals(List.of(), service.getAccess(1L).getPayload().get("createLineages"));
			assertEquals(List.of("collection"), new java.util.ArrayList<>((java.util.Set<?>) service.getAccess(1L).getPayload().get("allowed")));
			assertFalse(content.isAllowed(1L, "receipt"));
			assertThrows(OpenSpecimenException.class, content::ensureUnrestrictedBulkAccess);
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
		} finally {
			AuthUtil.clearCurrentUser();
			AnnotationBeanConfigurerAspect.aspectOf().destroy();
			singleton.set(null, original); accessSingleton.set(null, originalObjectAccess);
		}
	}

	private interface Invocation { Object call(String method, Object[] args); }

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Invocation invocation) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
			if (method.getName().equals("toString")) return type.getSimpleName();
			if (method.getName().equals("hashCode")) return System.identityHashCode(object);
			if (method.getName().equals("equals")) return object == args[0];
			return invocation.call(method.getName(), args);
		});
	}

	private static class NoDatabaseTransactionManager extends AbstractPlatformTransactionManager {
		private int rollbacks;
		@Override protected Object doGetTransaction() { return new Object(); }
		@Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
		@Override protected void doCommit(DefaultTransactionStatus status) { }
		@Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
	}
}
