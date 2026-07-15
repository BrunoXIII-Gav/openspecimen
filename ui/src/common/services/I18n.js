
class I18n {
  msg(key, args) {
    return window.osI18n.global.t(key, args);
  }

  exists(key) {
    return key && window.osI18n.global.te(key);
  }
}

export default new I18n();
