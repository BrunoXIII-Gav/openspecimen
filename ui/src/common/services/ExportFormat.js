import {reactive} from 'vue';

class ExportFormat {
  state = reactive({visible: false, format: 'CSV'});

  choose() {
    // Ignore a second click while a format selection is already open.
    if (this.resolve) return Promise.resolve(null);
    this.state.format = 'CSV';
    this.state.visible = true;
    return new Promise(resolve => this.resolve = resolve);
  }

  finish(format = null) {
    const resolve = this.resolve;
    this.resolve = null;
    this.state.visible = false;
    if (resolve) resolve(format);
  }
}

export default new ExportFormat();
