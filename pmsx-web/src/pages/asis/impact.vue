<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { getNodeApi, searchNodesApi } from '@/api/asisApi'
import { getImpactApi } from '@/api/impactApi'
import { getTableApi } from '@/api/tableApi'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import AsisCrudBadges from '@/components/asis/AsisCrudBadges.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import type { ApiError, NodeSummary, NodeType } from '@/types/api'
import type { ImpactResult } from '@/types/r3'

// SCR-AS-009 영향도 분석 (FR-AS-028) — 테이블·컬럼·함수·API·SQL 을 바꾸면 어디가 영향받는가
const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const systemId = ref<string | null>(null)
const nodeType = ref<Exclude<NodeType, 'SCREEN'>>('TABLE')
const q = ref('')
const candidates = ref<NodeSummary[]>([])
const searching = ref(false)
const target = ref<NodeSummary | null>(null)
const columns = ref<string[]>([])
const column = ref<string | null>(null)
const result = ref<ImpactResult | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const openScreens = ref<string[]>([])
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const search = async () => {
  if (!systemId.value) return
  searching.value = true
  try {
    const page = await searchNodesApi(systemId.value, { q: q.value.trim() || undefined, type: nodeType.value, size: 30 })
    candidates.value = page.content
  } catch {
    candidates.value = []
  } finally {
    searching.value = false
  }
}

const pickTarget = async (n: NodeSummary | null, keepColumn = false) => {
  target.value = n
  if (!keepColumn) column.value = null
  columns.value = []
  result.value = null
  if (!n) return
  if (n.nodeType === 'TABLE') {
    columns.value = await getTableApi(n.nodeId).then((d) => d.columns.map((c) => c.name)).catch(() => [])
  }
  void run()
}

const run = async () => {
  if (!systemId.value || !target.value) return
  loading.value = true
  error.value = null
  try {
    result.value = await getImpactApi(systemId.value, target.value.nodeId, column.value)
    openScreens.value = result.value.screens.slice(0, 3).map((s) => s.screenId)
    void router.replace({ query: { systemId: systemId.value, targetId: target.value.nodeId, column: column.value ?? undefined } })
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

// 딥링크(?targetId=)로 들어오면 노드 정보를 읽어 대상을 채운다
const restoreFromQuery = async () => {
  const id = typeof route.query.targetId === 'string' ? route.query.targetId : null
  if (!id || target.value?.nodeId === id) return
  try {
    const n = await getNodeApi(id)
    if (n.nodeType !== 'SCREEN') nodeType.value = n.nodeType
    column.value = typeof route.query.column === 'string' ? route.query.column : null
    await pickTarget({ nodeId: n.nodeId, nodeType: n.nodeType, key: n.key, name: n.name, file: n.file }, true)
  } catch {
    // 대상을 찾지 못하면 직접 고르게 둔다
  }
}

const viewerLink = (screenId: string, elementId?: string) =>
  ({ path: `/asis/viewer/${systemId.value}/${screenId}`, query: elementId ? { el: elementId } : {} })

// ─────────── computed ───────────
const typeItems = computed(() => (['TABLE', 'FN', 'API', 'SQL', 'SP'] as const).map((v) => ({ title: t(`asis.edge.nodeTypes.${v}`), value: v })))
const candidateItems = computed(() => {
  const list = [...candidates.value]
  if (target.value && !list.some((c) => c.nodeId === target.value?.nodeId)) list.unshift(target.value)
  return list
})
const exportParams = computed(() => ({ systemId: systemId.value ?? '', targetId: target.value?.nodeId ?? '', column: column.value ?? '' }))

// ─────────── watch ───────────
watch([systemId, nodeType], () => { candidates.value = []; void search() })
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { void search() }, 300)
})
watch(systemId, (id) => { if (id) void restoreFromQuery() }, { immediate: true })
watch(column, (c, prev) => { if (target.value && c !== prev && result.value) void run() })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.asis.impact')" :subtitle="t('impact.subtitle')">
      <ExcelExportButton v-if="result" dataset-id="impact" :file-name="`${t('nav.asis.impact')}_${result.target.name}`" :params="exportParams" />
    </AppPageHeader>

    <VCard border flat class="mb-4">
      <VCardText>
        <VRow dense align="center">
          <VCol cols="12" md="3"><AsisSystemSelect v-model="systemId" /></VCol>
          <VCol cols="5" md="2"><VSelect v-model="nodeType" :items="typeItems" :label="t('impact.targetType')" hide-details data-test="impact-type" /></VCol>
          <VCol cols="7" md="4">
            <VAutocomplete
              :model-value="target"
              :items="candidateItems"
              :item-title="(n: NodeSummary) => n.name"
              :item-value="(n: NodeSummary) => n.nodeId"
              return-object
              :loading="searching"
              :label="t('impact.target')"
              :placeholder="t('impact.targetPlaceholder')"
              no-filter
              hide-details
              clearable
              data-test="impact-target"
              @update:search="(v: string) => (q = v ?? '')"
              @update:model-value="(n: NodeSummary | null) => pickTarget(n)"
            >
              <template #item="{ props: ip, item }">
                <VListItem v-bind="ip" :subtitle="item.raw.file ?? item.raw.key" />
              </template>
            </VAutocomplete>
          </VCol>
          <VCol v-if="target?.nodeType === 'TABLE'" cols="12" md="3">
            <VSelect v-model="column" :items="columns" :label="t('impact.column')" :hint="t('impact.columnHint')" persistent-hint clearable data-test="impact-column" />
          </VCol>
        </VRow>
      </VCardText>
    </VCard>

    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
    <VAlert v-if="!result && !loading" type="info" variant="tonal">{{ t('impact.pick') }}</VAlert>

    <template v-if="result">
      <div class="d-flex flex-wrap align-center ga-2 mb-3">
        <VChip size="small" variant="outlined" label>{{ t(`asis.edge.nodeTypes.${result.target.nodeType}`) }}</VChip>
        <span class="font-mono font-weight-bold">{{ result.target.name }}<template v-if="result.target.column">.{{ result.target.column }}</template></span>
        <VChip v-if="result.summary.truncated" size="small" color="warning" variant="tonal">{{ t('impact.truncated') }}</VChip>
      </div>
      <VRow class="mb-1" data-test="impact-summary">
        <VCol v-for="k in (['screens', 'elements', 'apis', 'srs'] as const)" :key="k" cols="6" md="3">
          <VCard border flat class="pa-4">
            <div class="text-caption text-muted">{{ t(`impact.sum.${k}`) }}</div>
            <div class="text-h4 font-weight-bold">{{ result.summary[k] }}</div>
          </VCard>
        </VCol>
      </VRow>

      <VRow>
        <VCol cols="12" lg="7">
          <VCard border flat :title="t('impact.screens')">
            <VCardText v-if="!result.screens.length" class="text-muted">{{ t('impact.noScreens') }}</VCardText>
            <VExpansionPanels v-else v-model="openScreens" multiple variant="accordion" flat data-test="impact-screens">
              <VExpansionPanel v-for="s in result.screens" :key="s.screenId" :value="s.screenId" class="border-b">
                <VExpansionPanelTitle>
                  <div class="d-flex flex-wrap align-center ga-2 w-100">
                    <span class="font-weight-medium">{{ s.name }}</span>
                    <span class="font-mono text-caption text-muted">{{ s.screenCode }}</span>
                    <VChip size="x-small" variant="tonal">{{ t('impact.elementsN', { n: s.elements.length }) }}</VChip>
                    <VSpacer />
                    <span class="text-caption text-muted">{{ s.owner ?? '-' }}</span>
                  </div>
                </VExpansionPanelTitle>
                <VExpansionPanelText>
                  <div class="text-caption text-muted mb-2">{{ s.menuPath.join(' › ') }}
                    <RouterLink :to="viewerLink(s.screenId)" class="ml-2">{{ t('impact.openScreen') }}</RouterLink>
                  </div>
                  <div v-for="e in s.elements" :key="e.elementId" class="el-row py-1">
                    <div class="d-flex align-center ga-2">
                      <RouterLink :to="viewerLink(s.screenId, e.elementId)" class="font-weight-medium" data-test="impact-element">{{ e.label }}</RouterLink>
                      <AsisCrudBadges v-if="e.crud" :crud="e.crud" />
                    </div>
                    <div class="path text-caption font-mono text-muted">
                      <template v-for="(p, i) in e.path" :key="i"><span v-if="i" class="mx-1">→</span>{{ p }}</template>
                    </div>
                  </div>
                </VExpansionPanelText>
              </VExpansionPanel>
            </VExpansionPanels>
          </VCard>
        </VCol>
        <VCol cols="12" lg="5">
          <VCard border flat :title="t('impact.code')" class="mb-4">
            <VCardText>
              <div class="text-subtitle-2 mb-1">API ({{ result.apis.length }})</div>
              <div v-for="a in result.apis" :key="a.nodeId" class="font-mono text-caption">{{ a.name }}</div>
              <div class="text-subtitle-2 mt-3 mb-1">{{ t('impact.serverFunctions') }} ({{ result.serverFunctions.length }})</div>
              <div v-for="f in result.serverFunctions" :key="f.nodeId" class="font-mono text-caption" :title="f.file ?? ''">{{ f.name }}</div>
              <div class="text-subtitle-2 mt-3 mb-1">
                {{ t('impact.batches') }} ({{ result.batches.length }})
                <VIcon v-if="result.batches.length" icon="ri-error-warning-line" color="warning" size="14" :title="t('impact.batchesHint')" />
              </div>
              <div v-for="b in result.batches" :key="b.nodeId" class="font-mono text-caption" :title="b.file ?? ''">{{ b.name }}</div>
              <div v-if="!result.batches.length" class="text-caption text-muted">{{ t('common.none') }}</div>
            </VCardText>
          </VCard>
          <VCard border flat :title="t('impact.history')" class="mb-4">
            <VList density="compact">
              <VListSubheader>SR ({{ result.srs.length }})</VListSubheader>
              <VListItem v-for="s in result.srs" :key="s.srId" :to="{ path: '/sm/srs', query: { id: s.srId } }">
                <VListItemTitle class="text-body-2"><span class="font-mono text-caption mr-1">{{ s.srNo }}</span>{{ s.title }}</VListItemTitle>
                <template #append><AppStatusChip :status="s.status" prefix="sm.srStatus" size="x-small" /></template>
              </VListItem>
              <VListSubheader>{{ t('impact.defects') }} ({{ result.defects.length }})</VListSubheader>
              <VListItem v-for="d in result.defects" :key="d.defectId" :to="{ path: '/quality/defects', query: { id: d.defectId } }">
                <VListItemTitle class="text-body-2"><span class="font-mono text-caption mr-1">{{ d.defectNo }}</span>{{ d.title }}</VListItemTitle>
                <template #append><AppStatusChip :status="d.status" prefix="qa.defectStatus" size="x-small" /></template>
              </VListItem>
            </VList>
          </VCard>
          <VCard border flat :title="t('impact.owners')">
            <VList density="compact">
              <VListItem v-for="o in result.owners" :key="o.userId" :title="o.name" :subtitle="o.reason" prepend-icon="ri-user-3-line" />
              <VListItem v-if="!result.owners.length" :title="t('common.none')" disabled />
            </VList>
          </VCard>
        </VCol>
      </VRow>
    </template>
  </VContainer>
</template>

<style scoped>
.el-row + .el-row {
  border-top: 1px dashed rgb(var(--v-theme-line));
}

.path {
  overflow-wrap: anywhere;
}
</style>
