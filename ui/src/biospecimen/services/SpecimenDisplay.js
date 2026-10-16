import i18n from '@/common/services/I18n.js';

function typeLabel(type) {
  const key = 'pvs.specimen_type.' + (type || '').toLowerCase().replace(/\W+/g, '_').replace(/^_|_$/g, '');
  return i18n.exists(key) ? i18n.msg(key) : type || i18n.msg('specimens.specimen');
}

function label(specimen = {}) {
  if (specimen.intermediate !== true || specimen.lineage != 'Derived') {
    return specimen.label || specimen.type || '';
  }

  const type = typeLabel(specimen.type);
  const source = specimen.intermediateSourceLabel || specimen.parentLabel;
  return source ? i18n.msg('specimens.intermediate_from', {type, source}) :
    i18n.msg('specimens.intermediate_material_type', {type});
}

function parentLabel(specimen = {}) {
  return specimen.parentIntermediate ? label({
    intermediate: true, lineage: 'Derived', type: specimen.parentType,
    intermediateSourceLabel: specimen.parentIntermediateSourceLabel
  }) : specimen.parentLabel;
}

export default {label, parentLabel};
