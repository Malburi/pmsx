<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { decideRelationApi, updateTableApi } from '@/api/tableApi'
import AsisCrudBadges from '@/components/asis/AsisCrudBadges.vue'
import AsisRelationItem from '@/components/asis/AsisRelationItem.vue'
import { useAuthStore } from '@/stores/auth'
import type { ApiError, Relation, RelationDecision, TableDetail, TableSummary } from '@/types/api'
import { RELATION_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// 테이블 정의서 상세 (FR-AS-017) — 컬럼·인덱스·관계·사용 화면
// systemId 를 주면 영향도 분석(SCR-AS-009) 진입 버튼을 보여 준다
const props = defineProps<{ table: TableDetail; subjectAreas: string[]; systemId?: string | null }>()
const emit = defineEmits<{
  updated: [summary: TableSummary]
  relationChanged: [relation: Relation]
  openTable: [tableId: string]
  openElement: [screenId: string, elementId: string | null]
  openErd: [tableId: string]
}>()

const { t } = useI18n()
const auth = useAuthStore()
const tab = ref<'columns' | 'indexes' | 'relations' | 'usedBy'>('columns')
const busyRelation = ref<string | null>(null)
const meta = reactive({ open: false, subjectArea: '' as string | null, comment: '', saving: false, error: null as string | null })

// ─────────── 헬퍼 ───────────
const decide = async (relationId: string, decision: RelationDecision) => {
  busyRelation.value = relationId
  try {
    emit('relationChanged', await decideRelationApi(relationId, decision))
    showSuccessToast('asis.relation.decided')
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.saveFailed')
  } finally {
    busyRelation.value = null
  }
}

const openMeta = () => {
  Object.assign(meta, { open: true, subjectArea: props.table.subjectArea, comment: props.table.comment ?? '', saving: false, error: null })
}

const saveMeta = async () => {
  meta.saving = true
  meta.error = null
  try {
    const summary = await updateTableApi(props.table.tableId, { subjectArea: meta.subjectArea?.trim() || null, comment: meta.comment.trim() || null })
    emit('updated', summary)
    meta.open = false
    showSuccessToast('common.saved')
  } catch (e) {
    const err = e as ApiError
    meta.error = err.code === 'FORBIDDEN' ? t('common.forbiddenAction') : (err.message ?? t('common.saveFailed'))
  } finally {
    meta.saving = false
  }
}

// ─────────── computed ───────────
const canDecide = computed(() => auth.hasAnyRole(RELATION_ROLES))
const columnHeaders = computed(() => [
  { title: '', key: 'keys', sortable: false, width: 70 },
  { title: t('asis.tables.columns'), key: 'name', sortable: false },
  { title: t('asis.tables.colType'), key: 'dataType', sortable: false },
  { title: t('asis.tables.colNull'), key: 'nullable', sortable: false, width: 60 },
  { title: t('asis.tables.colDefault'), key: 'default', sortable: false },
  { title: t('asis.tables.colComment'), key: 'comment', sortable: false },
])
</script>

<template>
  <div class="table-detail">
    <div class="d-flex flex-wrap align-center ga-2 mb-1">
      <h2 class="text-subtitle-1 font-weight-bold font-mono">{{ table.name }}</h2>
      <VChip size="x-small" variant="outlined" label>{{ table.schema }}</VChip>
      <VChip v-if="table.subjectArea" size="x-small" color="primary" variant="tonal" label>{{ table.subjectArea }}</VChip>
      <VSpacer />
      <VBtn size="small" variant="text" prepend-icon="ri-node-tree" @click="emit('openErd', table.tableId)">{{ t('asis.tables.openErd') }}</VBtn>
      <VBtn
        v-if="systemId"
        size="small"
        variant="text"
        prepend-icon="ri-git-branch-line"
        :to="{ path: '/asis/impact', query: { systemId, targetId: table.tableId } }"
        data-test="table-impact"
      >{{ t('impact.open') }}</VBtn>
      <VBtn v-if="canDecide" size="small" variant="tonal" prepend-icon="ri-edit-line" data-test="table-edit-meta" @click="openMeta">
        {{ t('asis.tables.editMeta') }}
      </VBtn>
    </div>
    <div class="text-body-2 text-muted mb-3">{{ table.comment ?? '-' }}</div>

    <VTabs v-model="tab" density="compact" color="primary" class="border-b border-line mb-3">
      <VTab value="columns">{{ t('asis.tables.columns') }} ({{ table.columns.length }})</VTab>
      <VTab value="indexes">{{ t('asis.tables.indexes') }} ({{ table.indexes.length }})</VTab>
      <VTab value="relations" data-test="table-tab-relations">{{ t('asis.tables.relations') }} ({{ table.relations.length }})</VTab>
      <VTab value="usedBy">{{ t('asis.tables.usedBy') }} ({{ table.usedBy.length }})</VTab>
    </VTabs>

    <VDataTable
      v-if="tab === 'columns'"
      :headers="columnHeaders"
      :items="table.columns"
      item-value="name"
      density="compact"
      :items-per-page="-1"
      hide-default-footer
      class="border rounded"
    >
      <template #item.keys="{ item }">
        <VChip v-if="item.pk" size="x-small" color="warning" variant="tonal" label class="mr-1">{{ t('asis.tables.pk') }}</VChip>
        <VChip v-if="item.fk" size="x-small" color="info" variant="tonal" label>{{ t('asis.tables.fk') }}</VChip>
      </template>
      <template #item.name="{ item }"><span class="font-mono">{{ item.name }}</span></template>
      <template #item.dataType="{ item }"><span class="font-mono text-caption">{{ item.dataType }}</span></template>
      <template #item.nullable="{ item }">{{ item.nullable ? 'Y' : 'N' }}</template>
      <template #item.default="{ item }"><span class="font-mono text-caption">{{ item.default ?? '' }}</span></template>
    </VDataTable>

    <template v-else-if="tab === 'indexes'">
      <div v-if="!table.indexes.length" class="text-body-2 text-muted">{{ t('asis.tables.noIndexes') }}</div>
      <VList v-else density="compact" class="border rounded py-0">
        <VListItem v-for="ix in table.indexes" :key="ix.name" :title="ix.name" :subtitle="ix.columns.join(', ')" class="font-mono">
          <template #append>
            <VChip v-if="ix.unique" size="x-small" variant="tonal" label>{{ t('asis.tables.unique') }}</VChip>
          </template>
        </VListItem>
      </VList>
    </template>

    <template v-else-if="tab === 'relations'">
      <div v-if="!table.relations.length" class="text-body-2 text-muted">{{ t('asis.tables.noRelations') }}</div>
      <div class="d-flex flex-column ga-2">
        <AsisRelationItem
          v-for="r in table.relations"
          :key="r.relationId"
          :relation="r"
          :focus-table-id="table.tableId"
          :can-decide="canDecide"
          :busy="busyRelation === r.relationId"
          @decide="(d: RelationDecision) => decide(r.relationId, d)"
          @open-table="(id: string) => id !== table.tableId && emit('openTable', id)"
        />
      </div>
    </template>

    <template v-else>
      <div v-if="!table.usedBy.length" class="text-body-2 text-muted">{{ t('asis.tables.noUsedBy') }}</div>
      <VList v-else density="compact" class="border rounded py-0">
        <VListItem
          v-for="(u, i) in table.usedBy"
          :key="`${u.screenId}-${u.elementId}-${i}`"
          prepend-icon="ri-cursor-line"
          @click="emit('openElement', u.screenId, u.elementId)"
        >
          <VListItemTitle>{{ u.screenName }}<template v-if="u.elementLabel"> › {{ u.elementLabel }}</template></VListItemTitle>
          <VListItemSubtitle class="font-mono">{{ u.sqlName }}</VListItemSubtitle>
          <template #append><AsisCrudBadges :crud="u.crud" /></template>
        </VListItem>
      </VList>
    </template>

    <VDialog v-model="meta.open" max-width="460">
      <VCard :title="t('asis.tables.editMeta')">
        <VCardText>
          <VCombobox v-model="meta.subjectArea" :items="subjectAreas" :label="t('asis.tables.subjectArea')" clearable class="mb-2" data-test="table-subject" />
          <VTextarea v-model="meta.comment" :label="t('asis.tables.comment')" rows="2" auto-grow maxlength="500" counter />
          <VAlert v-if="meta.error" type="error" variant="tonal" density="compact" class="mt-2" role="alert">{{ meta.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="meta.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="meta.saving" data-test="table-meta-save" @click="saveMeta">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </div>
</template>
