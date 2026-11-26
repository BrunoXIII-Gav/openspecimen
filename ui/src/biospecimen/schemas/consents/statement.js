export default {
  fields:  [
    {
      "type": "text",
      "labelCode": "consents.statement_code",
      "name": "statement.code",
      "validations": {
        "required": {
          "messageCode": "consents.statement_code_req"
        }
      }
    },
    {
      "type": "textarea",
      "labelCode": "consents.statement",
      "name": "statement.statement",
      "rows": 5,
      "maxlength": 4000,
      "validations": {
        "required": {
          "messageCode": "consents.statement_req"
        },
        "maxLength": {
          "params": 4000,
          "messageCode": "consents.statement_too_long"
        }
      }
    }
  ]
}
