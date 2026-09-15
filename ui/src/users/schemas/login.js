
export default {
  layout: {
    rows: [
      {
        fields: [
          {
            type: 'text',
            name: 'loginDetail.loginName',
            'md-type': true,
            placeholderCode: 'login.login_name',
            validations: {
              required: {
                messageCode: 'login.login_name_required'
              }
            },
            showWhen: '!!loginDetail.domainName && !externalAuth'
          }
        ]
      },
      {
        fields: [
          {
            type: 'password',
            name: 'loginDetail.password',
            'md-type': true,
            placeholderCode: 'login.password',
            validations: {
              required: {
                messageCode: 'login.password_required'
              }
            },
            showWhen: '!!loginDetail.domainName && !externalAuth'
          }
        ]
      },
      {
        fields: [
          {
            type: 'text',
            name: 'loginDetail.props.otp',
            'md-type': true,
            placeholderCode: 'login.otp',
            validations: {
              required: {
                messageCode: 'login.otp_required'
              }
            },
            showWhen: '!!loginDetail.domainName && otpAuthEnabled && !externalAuth'
          }
        ]
      },
    ]
  }
}
