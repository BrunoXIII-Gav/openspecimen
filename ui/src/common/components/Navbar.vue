<template>
  <div class="os-navbar">
    <div class="items">
      <div class="logo">
        <a :href="homeUrl" relopener="noopener" class="primary-home-link">
          <img :src="osLogo" v-if="navbarPrimaryLogoEnabled">
          <span class="home-link-text" v-else>Home</span>
        </a>
        <a class="deploy-logo" :href="deploySiteUrl" v-if="deploySiteLogo" target="_blank" rel="noopener">
          <img :src="deploySiteLogo">
        </a>
        <div class="deploy-env" v-if="deployEnvEnabled">
          <span>{{deployEnv}}</span>
        </div>
      </div>

      <div class="search" v-if="!minimalLogin">
        <os-search />
      </div>

      <div class="buttons">
        <os-add-to-favorites v-if="!minimalLogin && navbarFavoritesEnabled" />

        <os-new-stuff  v-if="!minimalLogin && navbarNewStuffEnabled" />

        <os-user-feedback v-if="!minimalLogin" />

        <os-about v-if="navbarHelpEnabled" />

        <os-notifs-overlay v-if="!minimalLogin && navbarNotificationsEnabled" />

        <os-ask-os v-if="navbarAskOsEnabled" />

        <div class="user-profile" v-os-tooltip.bottom="$t('common.user_profile')" v-if="authenticated">
          <button @click="toggleProfileMenu">
            <os-username-avatar :name="username" />
          </button>

          <os-overlay ref="userProfileMenu" @click="toggleProfileMenu">
            <ul class="user-profile-options">
              <li>
                <router-link :to="{name: 'UserDetail.Overview', params: {userId: $ui.currentUser.id}}">
                  <span>{{username}}</span>
                </router-link>
              </li>
              <li class="divider">
                <os-divider />
              </li>
              <li>
                <a @click="logout">
                  <span v-t="'common.logout'">Log Out</span>
                </a>
              </li>
            </ul>
          </os-overlay>
        </div>

        <slot :authenticated="authenticated" :minimal-login="minimalLogin" />
      </div>
    </div>

    <os-loading-bar ref="loadingBar" />
  </div>
</template>

<script>

import osLogo from '@/assets/images/os_logo.png';
import http from '@/common/services/HttpClient.js';
import loginSvc from '@/common/services/Login.js';
import routerSvc from '@/common/services/Router.js';
import settingSvc from '@/common/services/Setting.js';

import About          from '@/common/components/About';
import AddToFavorites from '@/common/components/AddToFavorites.vue';
import Feedback       from '@/common/components/Feedback';
import NewStuff       from '@/common/components/NewStuff';
import NotifsOverlay  from '@/common/components/NotifsOverlay';
import Search         from '@/common/components/Search';

const PORTAL_LOGOUT_MARKER = 'openspecimen.loggedOutToPortal';

export default {
  props: ['noLogin', 'hideButtons'],

  emits: ['single-logout'],

  components: {
    'os-about': About,
    'os-add-to-favorites': AddToFavorites,
    'os-user-feedback': Feedback,
    'os-new-stuff': NewStuff,
    'os-notifs-overlay': NotifsOverlay,
    'os-search': Search
  },

  data() {
    return {
      osLogo: osLogo
    }
  },

  async created() {
    http.addListener({
      callStarted:   () => this.incrCallCount(),
      callFailed:    () => this.decrCallCount(),
      callCompleted: () => this.decrCallCount(),
    });
  },

  computed: {
    authenticated: function() {
      return this.$ui.currentUser && this.$ui.currentUser.id > 0
    },

    minimalLogin: function() {
      return this.noLogin || this.hideButtons || !this.authenticated;
    },

    appProps: function() {
      return (this.$ui && this.$ui.global && this.$ui.global.appProps) || {};
    },

    deployEnvEnabled: function() {
      return this.appProps.deploy_env_enabled != false;
    },

    navbarPrimaryLogoEnabled: function() {
      return this.appProps.navbar_primary_logo_enabled != false;
    },

    navbarFavoritesEnabled: function() {
      return this.appProps.navbar_favorites_enabled != false;
    },

    navbarNewStuffEnabled: function() {
      return this.appProps.navbar_new_stuff_enabled != false;
    },

    navbarHelpEnabled: function() {
      return this.appProps.navbar_help_enabled != false;
    },

    navbarNotificationsEnabled: function() {
      return this.appProps.navbar_notifications_enabled != false;
    },

    navbarAskOsEnabled: function() {
      return this.appProps.navbar_ask_os_enabled != false;
    },

    siteAssets: function() {
      return (this.$ui && this.$ui.global && this.$ui.global.siteAssets) || {};
    },

    deployEnv: function() {
      if (this.appProps.deploy_env) {
        return this.appProps.deploy_env.toUpperCase();
      }

      return 'UNKNOWN';
    },

    deploySiteUrl: function() {
      return this.siteAssets.siteUrl || '';
    },

    deploySiteLogo: function() {
      return this.siteAssets.siteLogo || '';
    },

    username: function() {
      return this.$filters.username(this.$ui.currentUser);
    },

    homeUrl: function() {
      return routerSvc.getUrl('HomePage');
    }
  },

  methods: {
    buildPortalLogoutUrl: function(baseUrl) {
      const url = new URL(baseUrl, window.location.origin);
      url.searchParams.set('logged_out', '1');
      url.searchParams.set('source', 'openspecimen');
      return url.toString();
    },

    buildPortalBounceUrl: function(baseUrl) {
      const targetUrl = this.buildPortalLogoutUrl(baseUrl);
      return (
        window.location.origin +
        window.location.pathname +
        '#/portal-logout?target=' +
        encodeURIComponent(targetUrl)
      );
    },

    toggleProfileMenu: function(event) {
      this.$refs.userProfileMenu.toggle(event);
    },

    incrCallCount: function() {
      if (this.$refs && this.$refs.loadingBar) {
        this.$refs.loadingBar.increment();
      }
    },

    decrCallCount: function() {
      if (this.$refs && this.$refs.loadingBar) {
        this.$refs.loadingBar.decrement();
      }
    },

    logout: async function() {
      const currentDomain = this.$ui?.currentUser?.domain;
      const portalLogoutUrl = (this.appProps.portal_logout_url || '').trim();
      if (currentDomain && portalLogoutUrl) {
        try {
          const domains = await loginSvc.getAuthDomains();
          const domain = domains && domains.find(domain => domain.name == currentDomain);
          if (domain?.type == 'saml') {
            await loginSvc.logout();
            sessionStorage.setItem(PORTAL_LOGOUT_MARKER, '1');
            window.location.replace(this.buildPortalBounceUrl(portalLogoutUrl));
            return;
          }
        } catch (error) {
          console.error('Error performing portal redirect logout', error);
        }
      }

      const [{value: sloEnabled}]  = await settingSvc.getSetting('auth', 'single_logout');
      if (sloEnabled) {
        loginSvc.getIdpLogoutUrl(this.$ui.currentUser.domain).then(
          url => {
            this.$emit('single-logout', url);
            loginSvc.logout().then(() => routerSvc.goto('UserLogin'));
          }
        );
      } else {
        loginSvc.logout().then(() => routerSvc.goto('UserLogin'));
      }
    }
  }
}
</script>

<style scoped>

.os-navbar {
  display: block;
  height: 40px;
  width: 100%;
  background: #67271a;
  border-bottom: 1px solid #2d2220;
  color: #fff;
  font-weight: bold;
}

.os-navbar .items {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: row;
}

.os-navbar .items .logo {
  display: flex;
  flex: 1;
  flex-direction: row;
  padding: 0.15rem;
}

.os-navbar .items .logo img {
  height: 2rem;
}

.os-navbar .items .logo .primary-home-link {
  display: inline-flex;
  align-items: center;
  text-decoration: none;
}

.os-navbar .items .logo .home-link-text {
  color: #fff;
  font-size: 0.95rem;
  font-weight: 600;
  padding: 0rem 0.4rem;
}

.os-navbar .items .logo .deploy-logo {
  margin-left: 0.4rem;
}

.os-navbar .items .logo .deploy-env {
  font-size: 0.6rem;
  color: #fff;
  background-color: #c33;
  padding: 0.125rem 0.25rem;
  border-radius: 0.25rem;
  display: inline-block;
  height: 1.125rem;
  margin-left: 0.4rem;
  margin-top: 0.2rem;
}

.os-navbar .items .search {
  display: flex;
  flex: 2;
}

.os-navbar .items .buttons {
  display: flex;
  flex: 1;
  justify-content: flex-end;
  align-items: center;
  margin-right: 0.67rem;
}

.buttons :deep(button) {
  background: transparent;
  border: none;
  color: #fff;
  font-weight: bold;
  font-size: 1.2rem;
  cursor: pointer;
  margin: 0rem 0.5rem;
}

.user-profile {
  display: inline-block;
}

.user-profile button {
  padding-top: 0rem;
}

.user-profile-options {
  margin: -1.25rem;
  list-style: none;
  padding: 0.5rem 0rem;
}

.user-profile-options li a {
  display: inline-block;
  padding: 0.75rem 1rem;
  transition: box-shadow 0.15s;
  text-decoration: none;
  color: inherit;
  width: 100%;
}

.user-profile-options li:not(.divider):hover {
  background: #e9ecef;
}

.user-profile-options li.divider {
  padding: 0.25rem 0rem;
  margin: -1rem 0rem;
}
</style>
