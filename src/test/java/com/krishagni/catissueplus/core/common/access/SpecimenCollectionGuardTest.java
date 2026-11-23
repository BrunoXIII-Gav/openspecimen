package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;
import com.krishagni.catissueplus.core.biospecimen.domain.*;
import com.krishagni.catissueplus.core.administrative.domain.PermissibleValue;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.*;
import com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenCollectionGuard;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import static org.junit.Assert.*;

public class SpecimenCollectionGuardTest {
  private final Map<Long, Specimen> specimens = new HashMap<>();
  private final Map<Long, SpecimenRequirement> requirements = new HashMap<>();
  private final Map<Long, Specimen> requirementParents = new HashMap<>();
  private final SpecimenDao specimenDao = proxy(SpecimenDao.class, (method, args) -> {
    if (method.equals("getById")) return specimens.get(args[0]);
    if (method.equals("getSpecimenByVisitAndSr")) return null;
    if (method.equals("getParentSpecimenByVisitAndSr")) return requirementParents.get(args[1]);
    if (method.equals("getByLabel") || method.equals("getByLabelAndCp")) return specimens.values().stream()
      .filter(item -> Objects.equals(item.getLabel(), args[args.length - 1])).findFirst().orElse(null);
    throw new AssertionError("Unexpected DAO operation: " + method);
  });
  private final DaoFactory daos = proxy(DaoFactory.class, (method, args) -> {
    if (method.equals("getSpecimenDao")) return specimenDao;
    if (method.equals("getSpecimenRequirementDao")) return proxy(SpecimenRequirementDao.class,
      (name, values) -> requirements.get(values[0]));
    throw new AssertionError(method);
  });
  private final SpecimenCollectionGuard guard = new SpecimenCollectionGuard(daos);

  @Test public void aChildOfAnExistingCollectedParentCanBeCollectedWithoutChangingTheParent() {
    Specimen parent = specimen(1L, "New", "Collected", null);
    SpecimenDetail child = input(null, "Derived", 1L);
    guard.validate(List.of(child));
    assertEquals("Collected", parent.getCollectionStatus());
    assertNull(child.getId());
  }

  @Test public void pendingMissedUncollectedDeletedAndMissingParentsAreRejected() {
    for (String status : List.of("Pending", "Missed Collection", "Not Collected")) {
      specimen(1L, "New", status, null);
      assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(input(null, "Aliquot", 1L))));
    }
    specimen(1L, "New", "Collected", null).setActivityStatus("Disabled");
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(input(null, "Derived", 1L))));
    specimens.clear();
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(input(null, "Derived", 1L))));
  }

  @Test public void submittingThePendingParentAndChildTogetherDoesNotSatisfyTheDependency() {
    Specimen parent = specimen(1L, "New", "Pending", null);
    SpecimenDetail root = input(1L, "New", null);
    root.setChildren(List.of(input(null, "Derived", null)));
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(root)));
    assertEquals("Pending", parent.getCollectionStatus());
  }

  @Test public void markingAChildMissedOrNotCollectedAlsoRequiresItsParentToBeCollected() {
    specimen(1L, "New", "Pending", null);
    for (String status : List.of("Missed Collection", "Not Collected")) {
      SpecimenDetail child = input(null, "Derived", 1L); child.setStatus(status);
      assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(child)));
    }
  }

  @Test public void anExistingChildUsesItsRealParentDespiteSpoofedLineageAndParentId() {
    Specimen pending = specimen(1L, "New", "Pending", null);
    specimen(2L, "New", "Collected", null);
    specimen(3L, "Aliquot", "Pending", pending);
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(input(3L, "New", 2L))));
  }

  @Test public void plannedChildrenUseTheRequirementParentDespiteSpoofedInput() {
    SpecimenRequirement primary = new SpecimenRequirement(); primary.setId(10L); primary.setLineage("New");
    SpecimenRequirement derivative = new SpecimenRequirement(); derivative.setId(11L); derivative.setLineage("Derived");
    derivative.setParentSpecimenRequirement(primary); requirements.put(11L, derivative);
    requirementParents.put(11L, specimen(1L, "New", "Pending", null));
    specimen(2L, "New", "Collected", null);
    SpecimenDetail child = input(null, "New", 2L); child.setReqId(11L); child.setVisitId(42L);
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(child)));
    specimens.get(1L).setCollectionStatus("Collected");
    guard.validate(List.of(child));
  }

  @Test public void primariesAndCollectedAncestorReferencesRemainValidButADeepPendingParentDoesNot() {
    guard.validate(List.of(input(null, "New", null)));
    Specimen root = specimen(1L, "New", "Collected", null);
    Specimen child = specimen(2L, "Derived", "Collected", root);
    SpecimenDetail reference = new SpecimenDetail(); reference.setId(1L);
    SpecimenDetail second = new SpecimenDetail(); second.setId(2L);
    second.setChildren(List.of(input(null, "Aliquot", null))); reference.setChildren(List.of(second));
    guard.validate(List.of(reference));
    child.setCollectionStatus("Pending"); second.setStatus("Collected");
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(reference)));
  }

  @Test public void collectedPrimaryWithoutReceiptBlocksBothKindsOfChildrenAndDoesNotConsumeQuantity() {
    Specimen primary = specimen(1L, "New", "Collected", null);
    primary.setAvailableQuantity(java.math.BigDecimal.TEN);
    for (String quality : Arrays.asList(null, "To be Received")) {
      PermissibleValue value = new PermissibleValue(); value.setValue(quality); primary.setReceivedQuality(quality == null ? null : value);
      for (String lineage : List.of("Derived", "Aliquot")) {
        assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(input(null, lineage, 1L))));
        Specimen child = specimen(2L, lineage, "Collected", primary);
        assertTrue(assertThrows(OpenSpecimenException.class, () -> primary.addChildSpecimen(child)).containsError(
          com.krishagni.catissueplus.core.biospecimen.domain.factory.SpecimenErrorCode.PRIMARY_RECEIPT_REQUIRED));
        assertEquals(java.math.BigDecimal.TEN, primary.getAvailableQuantity());
        assertTrue(primary.getChildCollection().isEmpty());
      }
    }
  }

  @Test public void aCollectedDerivativeDoesNotBypassReceiptOfItsOriginalPrimary() {
    Specimen primary = specimen(1L, "New", "Collected", null); primary.setReceivedQuality(null);
    specimen(2L, "Derived", "Collected", primary);
    for (String lineage : List.of("Derived", "Aliquot")) {
      assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(input(null, lineage, 2L))));
    }
    PermissibleValue quality = new PermissibleValue(); quality.setValue("Acceptable"); primary.setReceivedQuality(quality);
    guard.validate(List.of(input(null, "Aliquot", 2L)));
  }

  @Test public void pendingRequirementsRemainAllowedButReceiptInTheSameCollectionRequestCannotBypassTheCheck() {
    Specimen primary = specimen(1L, "New", "Collected", null); primary.setReceivedQuality(null);
    SpecimenDetail pending = input(null, "Derived", 1L); pending.setStatus("Pending");
    guard.validate(List.of(pending));
    SpecimenDetail root = input(1L, "New", null);
    var event = new com.krishagni.catissueplus.core.biospecimen.events.ReceivedEventDetail(); event.setReceivedQuality("Acceptable");
    root.setReceivedEvent(event); root.setChildren(List.of(input(null, "Derived", 1L)));
    assertThrows(OpenSpecimenException.class, () -> guard.validate(List.of(root)));
    assertFalse(primary.isReceived());
  }

  @Test public void primaryReceiptReadinessCannotBeSuppliedByTheClient() throws Exception {
    var detail = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"primaryReceived\":true}", SpecimenDetail.class);
    assertNull(detail.getPrimaryReceived());
  }

  private Specimen specimen(Long id, String lineage, String status, Specimen parent) {
    Specimen result = new Specimen(); result.setId(id); result.setLabel("S-" + id);
    result.setLineage(lineage); result.setCollectionStatus(status); result.setActivityStatus("Active");
    result.setParentSpecimen(parent);
    if ("New".equals(lineage)) { PermissibleValue quality = new PermissibleValue(); quality.setValue("Acceptable"); result.setReceivedQuality(quality); }
    specimens.put(id, result); return result;
  }
  private SpecimenDetail input(Long id, String lineage, Long parent) {
    SpecimenDetail result = new SpecimenDetail(); result.setId(id); result.setLineage(lineage);
    result.setParentId(parent); result.setStatus("Collected"); return result;
  }
  private interface Call { Object invoke(String method, Object[] args); }
  private static <T> T proxy(Class<T> type, Call call) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
      (object, method, args) -> call.invoke(method.getName(), args)));
  }
}
