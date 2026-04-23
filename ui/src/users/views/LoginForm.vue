<template>
  <FormCard ref="loginForm" :schema="loginSchema" :data="ctx" :logo="osLogo" @keydown.enter.prevent="login">
    <template #title v-if="showHeader">
      <h3 class="header">
        {{ ctx.showLoginAppName ? $t('login.sign_in_to_continue_to_os') : $t('login.sign_in_to_continue') }}
      </h3>
    </template>

    <template #primary-action>
      <os-button primary label="Sign In" @click="login" />
    </template>
  
    <template #secondary-actions v-if="ctx.forgotPasswordEnabled || ctx.otpAuthEnabled">
      <os-button text label="Forgot Password?" @click="gotoForgotPassword" v-if="ctx.forgotPasswordEnabled" />

      <os-button text label="Reset OTP Secret Code?" @click="gotoResetOtpSecret" v-if="ctx.otpAuthEnabled" />
    </template>
  </FormCard>
</template>

<script>
import loginSvc    from '@/common/services/Login.js';
import loginSchema from '@/users/schemas/login.js';
import osLogo      from '@/assets/images/os_dna_logo.png';
import routerSvc   from '@/common/services/Router.js';

import FormCard    from './FormCard.vue';

export default {
  props: ['show-header'],

  inject: ['ui'],

  components: {
    FormCard
  },

  data() {
    const {global: {appProps}} = this.ui;
    const domainName = appProps.default_domain || 'openspecimen';
    return {
      osLogo: appProps.login_logo_enabled === false ? null : osLogo,

      loginSchema: loginSchema.layout,

      ctx: {
        loginDetail: {
          domainName: domainName
        },

        getDomains: async () => this.domains,

        otpAuthEnabled: false,

        forgotPasswordEnabled: appProps.forgot_password,

        showLoginAppName: appProps.login_app_name_enabled !== false,

        externalAuth: false
      }
    };
  },

  mounted() {
    if (!this._isRedirectLoginFlow()) {
      localStorage.removeItem('osReqState');
    }

    this._getDomains().then(
      domains => {
        this.domains = domains.filter(domain => domain.allowLogins);
        if (!this.domains.some(domain => domain.name == this.ctx.loginDetail.domainName)) {
          this.ctx.loginDetail.domainName = this.domains.find(domain => domain.name == 'openspecimen')?.name || this.domains[0]?.name;
        }

        this._toggleExternalAuthSelected();
      }
    );

    if (this.$osSvc.userOtpSvc) {
      this.$osSvc.userOtpSvc.isFeatureEnabled().then(status => this.ctx.otpAuthEnabled = status);
    }
  },

  computed: {
  },

  watch: {
    'ctx.loginDetail.domainName': function() {
      this._toggleExternalAuthSelected();
      if (this.ctx.externalAuth) {
        loginSvc.gotoIdp(this.ctx.selectedDomain);
      }
    }
  },

  methods: {
    login: function() {
      if (!this.$refs.loginForm.validate()) {
        return;
      }

      if (this.ctx.externalAuth) {
        loginSvc.gotoIdp(this.ctx.selectedDomain);
        return;
      }

      const {loginDetail} = this.ctx;
      loginSvc.login(loginDetail).then(
        (resp) => {
          if (resp.resetPasswordToken && !resp.token) {
            routerSvc.goto('UserResetPassword', {}, {resetToken: resp.resetPasswordToken});
          } else {
            this._gotoPostLoginView();
          }
        }
      );
    },

    gotoForgotPassword: function() {
      routerSvc.goto('UserForgotPassword');
    },

    gotoResetOtpSecret: function() {
      routerSvc.goto('UserResetOtpSecretCode');
    },

    _toggleExternalAuthSelected: function() {
      const {loginDetail: {domainName}} = this.ctx;
      const domain = this.ctx.selectedDomain = (this.domains || []).find(d => d.name == domainName);
      this.ctx.externalAuth = (domain && (domain.type == 'saml' || domain.type == 'oauth')) || false;
    },

    _getDomains: function() {
      return loginSvc.getAuthDomains();
    },

    _gotoPostLoginView: function() {
      const reqState = this._isRedirectLoginFlow() ? this._consumeReqState() : null;
      if (reqState && reqState.name) {
        routerSvc.goto(reqState.name, reqState.params, reqState.query);
      } else {
        localStorage.removeItem('osReqState');
        routerSvc.goto('HomePage');
      }
    },

    _consumeReqState: function() {
      const reqState = localStorage.getItem('osReqState');
      localStorage.removeItem('osReqState');
      if (!reqState) {
        return null;
      }

      try {
        const {name, params, query} = JSON.parse(reqState);
        return {name, params: params || {}, query: query || {}};
      } catch (e) {
        console.log('Error processing the req state: ' + reqState);
        console.error(e);
        return null;
      }
    },

    _isRedirectLoginFlow: function() {
      const {query = {}} = routerSvc.getCurrentRoute();
      return query.redirect == true || query.redirect == 'true';
    }
  }
}
</script>
