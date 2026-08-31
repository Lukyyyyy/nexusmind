import { computed, onScopeDispose, ref } from 'vue';
import { useIntervalFn } from '@vueuse/core';

/**
 * count down
 *
 * @param seconds - count down seconds
 */
export default function useCountDown(seconds: number) {
  const count = ref(0);
  const isCounting = computed(() => count.value > 0);
  const { pause, resume } = useIntervalFn(
    () => {
      if (count.value > 1) {
        count.value -= 1;
      } else {
        stop();
      }
    },
    1000,
    { immediate: false }
  );

  function start(updateSeconds: number = seconds) {
    count.value = updateSeconds;
    resume();
  }

  function stop() {
    count.value = 0;
    pause();
  }

  onScopeDispose(() => {
    pause();
  });

  return {
    count,
    isCounting,
    start,
    stop
  };
}
