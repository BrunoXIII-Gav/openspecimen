const clinicalResources = ['ParticipantPhi', 'ParticipantDeid', 'Visit', 'Specimen', 'PrimarySpecimen'];
const contentOperations = ['Read', 'Create', 'Update'];
const operationFlags = {Read: 'read', Create: 'create', Update: 'update'};

export function hasRolePermission(acl, resources, operations) {
  return (acl || []).some(entry => {
    const resource = entry.name || entry.resourceName;
    if (resource !== 'ALL' && !resources.includes(resource)) return false;
    // The editor's flags are authoritative, including a checkbox just switched off.
    if (Object.prototype.hasOwnProperty.call(entry, 'name')) {
      return operations.some(operation => entry[operationFlags[operation]] === true);
    }
    return (entry.operations || []).some(({operationName}) => operationName === 'ALL' || operations.includes(operationName));
  });
}

export function roleContentDependencies(acl) {
  const allSpecimens = hasRolePermission(acl, ['Specimen'], ['Create']);
  const primaries = allSpecimens || hasRolePermission(acl, ['PrimarySpecimen'], ['Create']);
  return {
    content: hasRolePermission(acl, clinicalResources, contentOperations),
    creation: primaries || allSpecimens,
    lineages: {New: primaries, Derived: allSpecimens, Aliquot: allSpecimens}
  };
}

export function canConfigureContentOption(acl, option) {
  if (option.key === 'collection' || option.key === 'receipt') {
    return hasRolePermission(acl, ['Specimen', 'PrimarySpecimen'], contentOperations);
  }
  if (!Array.isArray(option.entityTypes)) return roleContentDependencies(acl).content;
  return option.entityTypes.some(type => {
    let resources = [];
    if (type.startsWith('SpecimenCollectionGroup') || type.startsWith('Visit')) resources = ['Visit'];
    else if (type === 'SpecimenDerivedExtension' || type === 'SpecimenAliquotExtension') resources = ['Specimen'];
    else if (type.startsWith('Specimen')) resources = ['Specimen', 'PrimarySpecimen'];
    else if (type.startsWith('Participant') || type === 'CommonParticipant') resources = ['ParticipantPhi', 'ParticipantDeid'];
    return hasRolePermission(acl, resources, contentOperations);
  });
}
