<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Relation, RelationDecision } from '@/types/api'
import { formatPercent } from '@/utils/format'

// 테이블 관계 한 건 — 종류(FK·추정·수동)·판단·근거 표시와 확정/기각 (FR-AS-018)
const props = withDefaults(defineProps<{
  relation: Relation
  /** 이 테이블 기준으로 볼 때 (상대 테이블을 강조) */
  focusTableId?: string | null
  canDecide?: boolean
  busy?: boolean
}>(), { focusTableId: null, canDecide: false, busy: false })
const emit = defineEmits<{ decide: [decision: RelationDecision]; openTable: [tableId: string] }>()

const { t } = useI18n()

// ─────────── computed ───────────
const kindColor = computed(() => ({ fk: 'success', inferred: 'warning', manual: 'src-manual' }[props.relation.kind]))
const decisionColor = computed(() => ({ pending: undefined, confirmed: 'success', rejected: 'error' }[props.relation.decision]))
const role = computed(() => {
  if (!props.focusTableId) return null
  return props.relation.childTableId === props.focusTableId ? 'child' : 'parent'
})
</script>

<template>
  <div class="rel" :class="{ rejected: relation.decision === 'rejected' }" data-test="relation-item">
    <div class="d-flex flex-wrap align-center ga-1 font-mono text-body-2">
      <button type="button" class="tbl-link" @click="emit('openTable', relation.childTableId)">{{ relation.childName }}</button>
      <VIcon icon="ri-arrow-right-line" size="14" />
      <button type="button" class="tbl-link" @click="emit('openTable', relation.parentTableId)">{{ relation.parentName }}</button>
      <VChip v-if="role" size="x-small" variant="outlined" class="ml-1">{{ role === 'child' ? t('asis.tables.childOf') : t('asis.tables.parentOf') }}</VChip>
    </div>
    <div class="text-caption font-mono text-muted cols">
      {{ relation.columns.map(([c, p]) => (c === p ? c : `${c} → ${p}`)).join(', ') }}
    </div>
    <div class="d-flex flex-wrap align-center ga-1 mt-1">
      <VChip size="x-small" :color="kindColor" variant="tonal" label>{{ t(`asis.relation.kind.${relation.kind}`) }}</VChip>
      <VChip size="x-small" :color="decisionColor" variant="tonal" label>{{ t(`asis.relation.decision.${relation.decision}`) }}</VChip>
      <span v-if="relation.evidenceCount !== null" class="text-caption text-muted">{{ t('asis.relation.evidence', { n: relation.evidenceCount }) }}</span>
      <span v-if="relation.confidence !== null" class="text-caption text-muted">· {{ t('asis.relation.confidence', { v: formatPercent(relation.confidence) }) }}</span>
      <VSpacer />
      <template v-if="canDecide && relation.kind !== 'fk'">
        <VBtn
          v-if="relation.decision !== 'confirmed'"
          size="x-small"
          variant="tonal"
          color="success"
          :loading="busy"
          data-test="relation-confirm"
          @click="emit('decide', 'confirmed')"
        >{{ t('asis.relation.confirm') }}</VBtn>
        <VBtn v-if="relation.decision !== 'rejected'" size="x-small" variant="tonal" color="error" :loading="busy" @click="emit('decide', 'rejected')">
          {{ t('asis.relation.reject') }}
        </VBtn>
        <VBtn v-if="relation.decision !== 'pending'" size="x-small" variant="text" :loading="busy" @click="emit('decide', 'pending')">
          {{ t('asis.relation.reset') }}
        </VBtn>
      </template>
    </div>
  </div>
</template>

<style scoped>
.rel {
  padding: 8px 10px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 6px;
  background: rgb(var(--v-theme-surface-2));
}

.rel.rejected {
  opacity: 0.6;
}

.tbl-link {
  border: 0;
  background: none;
  color: rgb(var(--v-theme-primary));
  font: inherit;
  cursor: pointer;
  padding: 0;
}

.tbl-link:hover {
  text-decoration: underline;
}

.cols {
  word-break: break-all;
}
</style>
