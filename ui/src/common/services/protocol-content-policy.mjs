export function allows(access, key) {
  return !!access && (access.unrestricted === true || (access.allowed || []).includes(key));
}

export function canCreate(access, lineage) {
  return !!access && ['New', 'Derived', 'Aliquot'].includes(lineage) &&
    (!Array.isArray(access.createLineages) || access.createLineages.includes(lineage));
}

export function allowsExtension(access, type, formId) {
  return !(access.blockedExtensions || []).includes(type) && allows(access, 'form:' + formId);
}

export function filterFields(fields, access, extensionType) {
  return (fields || []).filter(field => {
    const name = field.name || '';
    if (name.startsWith('specimen.collectionEvent.')) return allows(access, 'collection');
    if (name.startsWith('specimen.receivedEvent.')) return allows(access, 'receipt');
    if (field.contentKey) return allows(access, field.contentKey);
    if (field.formId) return allows(access, 'form:' + field.formId);
    const extensionKey = (access.extensionKeys || {})[extensionType];
    if ((access.blockedExtensions || []).includes(extensionType) && name.includes('.extensionDetail.')) return false;
    if (extensionKey && name.includes('.extensionDetail.')) return allows(access, extensionKey);
    return true;
  });
}

export function stripRestrictedData(specimen, access) {
  if (!allows(access, 'collection')) delete specimen.collectionEvent;
  if (!allows(access, 'receipt')) delete specimen.receivedEvent;
  const type = {New: 'SpecimenPrimaryExtension', Derived: 'SpecimenDerivedExtension', Aliquot: 'SpecimenAliquotExtension'}[specimen.lineage] || 'SpecimenExtension';
  const key = (access.extensionKeys || {})[type] || (access.extensionKeys || {}).SpecimenExtension;
  if ((access.blockedExtensions || []).includes(type) || (key && !allows(access, key))) delete specimen.extensionDetail;
  for (const child of specimen.children || []) stripRestrictedData(child, access);
  return specimen;
}
