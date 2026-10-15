<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getMenuTreeApi, getSystemCrudApi } from '@/api/asisApi'
import AsisCrudBadges from '@/components/asis/AsisCrudBadges.vue'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import { useApi } from '@/composables/useApi'
import type { CrudMatrix, MenuNode } from '@/types/api'
import { focusableRow } from '@/utils/a11y'
import { CRUD_LETTERS, crudColor } from '@/utils/asis'

// SCR-AS-007 CRUD 매트릭스 (FR-AS-015) — 행이 수천 개여도 가상 스크롤로 그린다
type Row = CrudMatrix['rows'][number]

const { t } = useI18n()
const router = useRouter()

const systemId = ref<string | null>(null)
const axis = ref<'screen' | 'element'>('screen')
const menuId = ref<string | null>(null)
const tableFilter = ref('')
const rowFilter = ref('')
const { data: matrix, loading, error, execute } = useApi<CrudMatrix>(
  (id: string, by: 'screen' | 'element', m?: string) => getSystemCrudApi(id, by, m),
)
const { data: menu, execute: loadMenu } = useApi<MenuNode[]>((id: string) => getMenuTreeApi(id))

// ─────────── 헬퍼 ───────────
const load = () => {
  if (systemId.value) void execute(systemId.value, axis.value, menuId.value ?? undefined)
}

const openRow = (row: Row) => {
  if (axis.value === 'screen') void router.push(`/asis/viewer/${systemId.value}/${row.elementId}`)
  else if (row.screenId) void router.push({ path: `/asis/viewer/${systemId.value}/${row.screenId}`, query: { el: row.elementId } })
}

const onRowClick = (_e: Event, payload: { item: Row }) => openRow(payload.item)

const nodeIdOf = (h: unknown) => (h as { nodeId: string }).nodeId

// ─────────── computed ───────────
const menuItems = computed(() => (menu.value ?? []).map((m) => ({ title: m.name, value: m.menuId })))
const tables = computed(() => {
  const f = tableFilter.value.trim().toUpperCase()
  return (matrix.value?.tables ?? []).filter((tb) => !f || tb.name.toUpperCase().includes(f))
})
const rows = computed(() => {
  const f = rowFilter.value.trim().toLowerCase()
  const visible = new Set(tables.value.map((tb) => tb.nodeId))
  return (matrix.value?.rows ?? []).filter((r) =>
    (!f || `${r.label} ${r.sub ?? ''}`.toLowerCase().includes(f))
    && Object.keys(r.cells).some((k) => visible.has(k)))
})
// 테이블별 합계 (열 머리에 C/R/U/D 가 몇 번 쓰였는지)
const totals = computed(() => {
  const out: Record<string, Record<string, number>> = {}
  tables.value.forEach((tb) => {
    out[tb.nodeId] = Object.fromEntries(CRUD_LETTERS.map((c) => [c, 0]))
    rows.value.forEach((r) => CRUD_LETTERS.forEach((c) => { if ((r.cells[tb.nodeId] ?? '').includes(c)) out[tb.nodeId][c] += 1 }))
  })
  return out
})
const headers = computed(() => [
  { title: t('asis.crudPage.corner', { axis: axis.value === 'screen' ? t('asis.crudPage.axisScreen') : t('asis.crudPage.axisElement') }), key: 'label', fixed: true, minWidth: 220, sortable: false },
  ...tables.value.map((tb) => ({ title: tb.name, key: `c_${tb.nodeId}`, sortable: false, minWidth: 96, align: 'center' as const, nodeId: tb.nodeId })),
])

// ─────────── watch ───────────
watch(systemId, (id) => {
  menuId.value = null
  if (id) void loadMenu(id)
  load()
})

watch([axis, menuId], load)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <div class="d-flex flex-wrap align-center ga-3 mb-4">
      <div class="flex-grow-1">
        <h1 class="pmsx-page-title">{{ t('nav.asis.crud') }}</h1>
        <div class="text-body-2 text-muted">{{ t('asis.crudPage.subtitle') }}</div>
      </div>
      <AsisSystemSelect v-model="systemId" />
    </div>

    <VAlert v-if="!systemId" type="info" variant="tonal">{{ t('asis.selectSystemFirst') }}</VAlert>
    <VCard v-else border flat>
      <VCardText>
        <VRow dense align="center">
          <VCol cols="12" sm="auto">
            <VBtnToggle v-model="axis" mandatory density="compact" variant="outlined" divided color="primary" data-test="crud-axis">
              <VBtn value="screen" size="small">{{ t('asis.crudPage.axisScreen') }}</VBtn>
              <VBtn value="element" size="small">{{ t('asis.crudPage.axisElement') }}</VBtn>
            </VBtnToggle>
          </VCol>
          <VCol cols="12" sm="6" md="3">
            <VSelect v-model="menuId" :items="menuItems" :label="t('asis.crudPage.menu')" clearable hide-details />
          </VCol>
          <VCol cols="12" sm="6" md="3">
            <VTextField v-model="rowFilter" :label="axis === 'screen' ? t('asis.crudPage.axisScreen') : t('asis.crudPage.axisElement')" prepend-inner-icon="ri-search-line" clearable hide-details />
          </VCol>
          <VCol cols="12" sm="6" md="3">
            <VTextField v-model="tableFilter" :label="t('asis.crudPage.tableFilter')" prepend-inner-icon="ri-table-line" clearable hide-details data-test="crud-table-filter" />
          </VCol>
          <VSpacer />
          <VCol cols="12" sm="auto" class="text-caption text-muted">
            {{ t('asis.crudPage.rows', { n: rows.length, m: tables.length }) }}
          </VCol>
        </VRow>
      </VCardText>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mx-4 mb-3">
        {{ error.message }}
        <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
      </VAlert>
      <VDataTableVirtual
        :headers="headers"
        :items="rows"
        :loading="loading"
        item-value="elementId"
        density="compact"
        fixed-header
        height="calc(100vh - 320px)"
        hover
        class="crud-grid"
        :no-data-text="t('asis.crudPage.empty')"
        data-test="crud-grid"
        :row-props="focusableRow"
        @click:row="onRowClick"
      >
        <template v-for="h in headers.slice(1)" :key="h.key" #[`header.${h.key}`]>
          <div class="th-table">
            <div class="font-mono">{{ h.title }}</div>
            <div class="th-totals">
              <template v-for="c in CRUD_LETTERS" :key="c">
                <span v-if="totals[nodeIdOf(h)]?.[c]" :class="`text-${crudColor(c)}`">{{ c }}{{ totals[nodeIdOf(h)][c] }}</span>
              </template>
            </div>
          </div>
        </template>
        <template #item="{ item, props: rowProps }">
          <tr v-bind="rowProps" class="crud-row" tabindex="0" @keydown.enter="openRow(item)">
            <td class="cell-label">
              <div class="text-body-2">{{ item.label }}</div>
              <div v-if="item.sub" class="text-caption text-muted">{{ item.sub }}</div>
            </td>
            <td v-for="tb in tables" :key="tb.nodeId" class="text-center">
              <AsisCrudBadges :crud="item.cells[tb.nodeId]" />
            </td>
          </tr>
        </template>
      </VDataTableVirtual>
    </VCard>
  </VContainer>
</template>

<style scoped>
.crud-grid :deep(th) {
  white-space: nowrap;
}

.th-table {
  line-height: 1.3;
  padding: 2px 0;
}

.th-totals {
  display: flex;
  gap: 4px;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
}

.crud-row {
  cursor: pointer;
}

.cell-label {
  position: sticky;
  left: 0;
  z-index: 1;
  background: rgb(var(--v-theme-surface));
  min-width: 220px;
  border-right: 1px solid rgb(var(--v-theme-line));
}
</style>
