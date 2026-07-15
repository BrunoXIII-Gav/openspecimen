<template>
  <div />
</template>

<script>

export default {
  created() {
    const target = this.$route?.query?.target || '';
    if (!target) {
      window.location.replace(window.location.origin + window.location.pathname + '#/login');
      return;
    }

    window.location.replace(target);
  }
}
</script>
