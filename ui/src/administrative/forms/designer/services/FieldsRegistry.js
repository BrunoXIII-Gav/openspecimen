
import i18n from "@/common/services/I18n.js";

class FieldsRegistry {
    fields = {};

    constructor() {

    }

    registerType(field) {
        this.fields[field.type] = field;
    }

    getTypes() {
        return this.fields;
    }

    getField(type) {
        return this.fields[type];
    }

    getDisplayLabel(type) {
        const field = this.getField(type) || {};
        return field.labelCode ? i18n.msg(field.labelCode) : field.label;
    }
}

const fields = [
    {
        type: "stringTextField",
        labelCode: "forms.designer.field_types.text_field",
    },
    {
        type: "textArea",
        labelCode: "forms.designer.field_types.text_area",
    },
    {
        type: "numberField",
        labelCode: "forms.designer.field_types.number_field",
    },
    {
        type: "radiobutton",
        labelCode: "forms.designer.field_types.radio_button",
        allowedInSubForm: false
    },
    {
        type: "checkbox",
        labelCode: "forms.designer.field_types.checkbox",
        allowedInSubForm: false
    },
    {
        type: "booleanCheckbox",
        labelCode: "forms.designer.field_types.yes_no_checkbox",
    },
    {
        type: "combobox",
        labelCode: "forms.designer.field_types.dropdown",
    },
    {
        type: "multiSelectListbox",
        labelCode: "forms.designer.field_types.multiselect_dropdown",
    },
    {
        type: "datePicker",
        labelCode: "forms.designer.field_types.date_picker",
    },
    {
        type: "fileUpload",
        labelCode: "forms.designer.field_types.file_upload",
    },
    {
        type: "signature",
        labelCode: "forms.designer.field_types.signature",
    },
    {
        type: "label",
        labelCode: "forms.designer.field_types.note",
        allowedInSubForm: false
    },
    {
        type: "userField",
        labelCode: "forms.designer.field_types.user",
    },
    {
        type: "pvField",
        labelCode: "forms.designer.field_types.permissible_value",
        validate: function (field) {
            if (!field.attribute) {
                return { status: false, error: i18n.msg('forms.designer.pv_attribute_required') };
            }

            return { status: true };
        }
    },
    {
        type: "siteField",
        labelCode: "forms.designer.field_types.site",
    },
    {
        type: "storageContainer",
        labelCode: "forms.designer.field_types.storage_container",
    },
    {
        type: "subForm",
        labelCode: "forms.designer.field_types.subform",
        allowedInSubForm: false
    },
];

const fr = new FieldsRegistry();
fields.forEach(field => fr.registerType(field));

export default fr;
