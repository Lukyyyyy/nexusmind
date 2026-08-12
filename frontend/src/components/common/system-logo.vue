<script lang="ts" setup>
defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <svg
    class="nexusmind-symbol"
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <path d="M16 11.5 8.3 7.1M16 11.5l7.7-4.4M16 11.5V6" />
    <circle cx="7" cy="6.4" r="2.4" />
    <circle cx="16" cy="4.2" r="2.4" />
    <circle cx="25" cy="6.4" r="2.4" />
    <path d="M16 13.2c-2.6-2.1-5.8-3.1-9.7-3.1v13.6c3.9 0 7.1 1.1 9.7 3.3V13.2Z" />
    <path d="M16 13.2c2.6-2.1 5.8-3.1 9.7-3.1v13.6c-3.9 0-7.1 1.1-9.7 3.3V13.2Z" />
  </svg>
</template>

<style scoped>
.nexusmind-symbol {
  display: block;
  width: 1em;
  height: 1em;
  overflow: visible;
  color: inherit;
}

.nexusmind-symbol path,
.nexusmind-symbol circle {
  stroke: currentcolor;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.nexusmind-symbol circle {
  fill: currentcolor;
  stroke-width: 0;
}
</style>
