angular.module('os.biospecimen.cp')
  .controller('CpFieldSettingsCtrl', function($scope, cp, sysDictData, cpDictData, CpConfigSvc, Alerts) {
    var EDITOR_META_KEY = 'osFieldsEditor';
    var MODULES = [
      {name: 'participant', title: 'participant.title', prefixes: ['cpr.']},
      {name: 'visit', title: 'visits.title', prefixes: ['visit.']},
      {name: 'specimen', title: 'specimen.title', prefixes: ['specimen.']}
    ];

    var savedState;
    var cpWorkflowData;
    var systemWorkflowData;

    function init() {
      cpWorkflowData = angular.copy(cpDictData || {});
      systemWorkflowData = angular.copy(sysDictData || {});

      $scope.fctx = {
        activeModule: 'participant',
        modules: buildModules(),
        modified: false,
        sortOpts: {
          axis: 'y',
          handle: '.os-drag-handle',
          placeholder: 'os-field-setting-placeholder',
          stop: function() {
            $scope.fctx.modified = true;
          }
        }
      };

      var firstModuleWithFields = $scope.fctx.modules.find(function(module) { return module.fields.length > 0; });
      if (firstModuleWithFields) {
        $scope.fctx.activeModule = firstModuleWithFields.name;
      } else {
        $scope.fctx.activeModule = undefined;
      }

      savedState = angular.copy($scope.fctx.modules);
    }

    function buildModules() {
      var orderedManagedFields = getOrderedManagedFields();
      var currentFields = getCurrentFieldsByName();

      return MODULES.map(
        function(module) {
          return {
            name: module.name,
            title: module.title,
            fields: orderedManagedFields
              .filter(function(field) { return belongsToModule(field, module); })
              .map(function(field) { return toEditableField(field, currentFields[field.name]); })
          };
        }
      );
    }

    function getOrderedManagedFields() {
      var sysFields = (systemWorkflowData.fields || []).filter(isManagedField);
      var cpFields  = (cpWorkflowData.fields || []).filter(isManagedField);

      var order = [];
      var byName = {};
      var authoritative = isAuthoritativeOverride(cpWorkflowData);

      if (authoritative) {
        cpFields.forEach(function(field) {
          byName[field.name] = field;
          order.push(field.name);
        });
      } else {
        sysFields.forEach(function(field) {
          byName[field.name] = field;
          order.push(field.name);
        });

        cpFields.forEach(function(field) {
          if (!byName[field.name]) {
            byName[field.name] = field;
            order.push(field.name);
          }
        });
      }

      sysFields.forEach(function(field) {
        if (!byName[field.name]) {
          byName[field.name] = field;
          order.push(field.name);
        }
      });

      cpFields.forEach(function(field) {
        if (!byName[field.name]) {
          byName[field.name] = field;
          order.push(field.name);
        }
      });

      return order.map(function(name) { return byName[name]; });
    }

    function getCurrentFieldsByName() {
      var result = {};
      var authoritative = isAuthoritativeOverride(cpWorkflowData);
      var sysFields = (systemWorkflowData.fields || []).filter(isManagedField);
      var cpFields  = (cpWorkflowData.fields || []).filter(isManagedField);

      sysFields.forEach(function(field) {
        result[field.name] = {
          field: angular.copy(field),
          visible: !authoritative
        };
      });

      cpFields.forEach(function(field) {
        result[field.name] = {
          field: angular.copy(field),
          visible: true
        };
      });

      return result;
    }

    function toEditableField(field, current) {
      var sysField = getFieldByName(systemWorkflowData.fields || [], field.name);
      var currentField = current ? current.field : field;
      var requiredBySystem = !!(sysField && sysField.optional === false);

      return {
        name: field.name,
        caption: currentField.caption || field.caption || field.name,
        type: currentField.type || field.type,
        visible: requiredBySystem || !current || current.visible !== false,
        required: requiredBySystem || currentField.optional === false,
        requiredBySystem: requiredBySystem,
        canHide: !requiredBySystem,
        canToggleRequired: !requiredBySystem,
        original: angular.copy(currentField)
      };
    }

    function isAuthoritativeOverride(data) {
      return !!(data && data[EDITOR_META_KEY] && data[EDITOR_META_KEY].version == 1);
    }

    function isManagedField(field) {
      return !!(field && field.name && !isExtensionField(field) && (
        field.name.indexOf('cpr.') == 0 ||
        field.name.indexOf('visit.') == 0 ||
        field.name.indexOf('specimen.') == 0
      ));
    }

    function isExtensionField(field) {
      return field.name.indexOf('.extensionDetail.attrsMap') >= 0;
    }

    function belongsToModule(field, module) {
      return module.prefixes.some(function(prefix) { return field.name.indexOf(prefix) == 0; });
    }

    function getFieldByName(fields, name) {
      return fields.find(function(field) { return field.name == name; });
    }

    function buildVisibleFields() {
      var visibleFields = [];
      $scope.fctx.modules.forEach(
        function(module) {
          module.fields.forEach(
            function(field) {
              if (!field.visible) {
                return;
              }

              var wfField = angular.copy(field.original);
              wfField.caption = field.caption;
              wfField.optional = !field.required;
              visibleFields.push(wfField);
            }
          );
        }
      );

      return visibleFields;
    }

    function getUnmanagedCpFields() {
      return (cpWorkflowData.fields || []).filter(function(field) { return !isManagedField(field); });
    }

    $scope.setActiveModule = function(moduleName) {
      $scope.fctx.activeModule = moduleName;
    };

    $scope.onFieldChange = function() {
      $scope.fctx.modified = true;
    };

    $scope.save = function() {
      var workflow = {
        name: 'dictionary',
        data: angular.copy(cpWorkflowData || {})
      };

      workflow.data.fields = buildVisibleFields().concat(getUnmanagedCpFields());
      workflow.data[EDITOR_META_KEY] = {version: 1};

      CpConfigSvc.saveWorkflow(cp.id, workflow).then(
        function() {
          cp.draftMode = true;
          cpWorkflowData = angular.copy(workflow.data);
          cpDictData = angular.copy(workflow.data);
          $scope.fctx.modules = buildModules();
          savedState = angular.copy($scope.fctx.modules);
          $scope.fctx.modified = false;
          Alerts.success('cp.field_settings.updated');
        }
      );
    };

    $scope.reset = function() {
      $scope.fctx.modules = angular.copy(savedState);
      $scope.fctx.modified = false;
    };

    init();
  });
