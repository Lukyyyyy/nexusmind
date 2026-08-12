<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="nexus-logo w-full flex-y-center nowrap-hidden px-20px">
    <span class="nexus-logo-mark"><SystemLogo class="text-30px" /></span>
    <h2 v-show="showTitle" class="pl-10px text-16px text-#182234 font-650 transition duration-300 ease-in-out">
      {{ $t('system.title') }}
    </h2>
  </RouterLink>
</template>

<style scoped></style>
