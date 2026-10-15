<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CRUD_LETTERS, crudColor } from '@/utils/asis'

const props = defineProps<{ crud: string | null | undefined }>()

const { t } = useI18n()

const letters = computed(() => CRUD_LETTERS.filter((c) => (props.crud ?? '').includes(c)))
</script>

<template>
  <span class="crud-badges">
    <span
      v-for="c in letters"
      :key="c"
      class="crud-badge"
      :class="`bg-${crudColor(c)}`"
      :title="t(`asis.crud.${c}`)"
    >{{ c }}</span>
  </span>
</template>

<style scoped>
.crud-badges {
  display: inline-flex;
  gap: 3px;
  vertical-align: middle;
}

.crud-badge {
  display: inline-block;
  min-width: 18px;
  padding: 0 4px;
  border-radius: 3px;
  font: 700 10px/16px var(--pmsx-mono);
  text-align: center;
}
</style>
