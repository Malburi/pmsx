<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getElementLinksApi } from '@/api/traceApi'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import DefectFormDialog from '@/components/quality/DefectFormDialog.vue'
import IssueFormDialog from '@/components/quality/IssueFormDialog.vue'
import TraceLinkEditor from '@/components/trace/TraceLinkEditor.vue'
import { useAuthStore } from '@/stores/auth'
import type { ElementSummary } from '@/types/api'
import type { ElementLinks } from '@/types/r2'
import { ISSUE_EDIT_ROLES, REQUIREMENT_EDIT_ROLES, TEST_EDIT_ROLES } from '@/utils/permission'
import { showSuccessToast } from '@/utils/toast'

// 뷰어 "연결 이력" 탭 (FR-AS-027) — 요구사항·SR·결함·이슈 연결 + 핫스팟에서 결함/이슈 바로 등록 (FR-QA-008)
const props = defineProps<{ element: ElementSummary; screenId: string | null; screenName?: string | null }>()

const { t } = useI18n()
const auth = useAuthStore()
const links = ref<ElementLinks | null>(null)
const loading = ref(false)
const defectOpen = ref(false)
const issueOpen = ref(false)

const load = async () => {
  loading.value = true
  try {
    links.value = await getElementLinksApi(props.element.elementId)
  } catch {
    links.value = { links: [], defects: [], issues: [] }
  } finally {
    loading.value = false
  }
}

const onSaved = (kind: 'defect' | 'issue') => {
  showSuccessToast(kind === 'defect' ? 'asis.links.defectCreated' : 'asis.links.issueCreated')
  void load()
}

const context = computed(() => t('asis.links.context', { screen: props.screenName ?? props.screenId ?? '', el: props.element.label || props.element.elementCode }))
const defectPreset = computed(() => ({ elementId: props.element.elementId, screenId: props.screenId, title: `[${props.screenName ?? ''} › ${props.element.label}] `, context: context.value }))
const issuePreset = computed(() => ({ elementId: props.element.elementId, title: `[${props.screenName ?? ''} › ${props.element.label}] `, context: context.value }))

watch(() => props.element.elementId, () => { void load() }, { immediate: true })
</script>

<template>
  <div data-test="element-links">
    <div class="d-flex flex-wrap ga-2 mb-3">
      <VBtn v-if="auth.hasAnyRole(TEST_EDIT_ROLES)" size="small" variant="tonal" color="error" prepend-icon="ri-bug-line" data-test="hotspot-defect" @click="defectOpen = true">{{ t('asis.links.addDefect') }}</VBtn>
      <VBtn v-if="auth.hasAnyRole(ISSUE_EDIT_ROLES)" size="small" variant="tonal" color="warning" prepend-icon="ri-error-warning-line" @click="issueOpen = true">{{ t('asis.links.addIssue') }}</VBtn>
    </div>
    <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
    <template v-if="links">
      <div class="text-subtitle-2 mb-1">{{ t('asis.links.defects') }} <VChip size="x-small" variant="tonal">{{ links.defects.length }}</VChip></div>
      <VList density="compact" class="py-0 mb-3">
        <VListItem v-for="d in links.defects" :key="d.defectId" class="px-0" :to="{ path: '/quality/defects', query: { id: d.defectId } }">
          <template #prepend><span class="font-mono text-caption mr-2">{{ d.defectNo }}</span></template>
          <VListItemTitle class="text-body-2">{{ d.title }}</VListItemTitle>
          <template #append>
            <AppStatusChip :status="d.severity" prefix="qa.severity" size="x-small" />
            <AppStatusChip :status="d.status" prefix="qa.defectStatus" size="x-small" class="ml-1" />
          </template>
        </VListItem>
        <div v-if="!links.defects.length" class="text-body-2 text-muted">{{ t('common.noData') }}</div>
      </VList>
      <div class="text-subtitle-2 mb-1">{{ t('asis.links.issues') }} <VChip size="x-small" variant="tonal">{{ links.issues.length }}</VChip></div>
      <VList density="compact" class="py-0 mb-3">
        <VListItem v-for="i in links.issues" :key="i.issueId" class="px-0" :to="{ path: '/quality/issues', query: { id: i.issueId } }">
          <template #prepend><span class="font-mono text-caption mr-2">{{ i.issueNo }}</span></template>
          <VListItemTitle class="text-body-2">{{ i.title }}</VListItemTitle>
          <template #append><AppStatusChip :status="i.status" prefix="qa.issueStatus" size="x-small" /></template>
        </VListItem>
        <div v-if="!links.issues.length" class="text-body-2 text-muted">{{ t('common.noData') }}</div>
      </VList>
    </template>
    <VDivider class="mb-3" />
    <TraceLinkEditor :id="element.elementId" type="ELEMENT" :readonly="!auth.hasAnyRole(REQUIREMENT_EDIT_ROLES)" />
    <DefectFormDialog v-model="defectOpen" :preset="defectPreset" @saved="onSaved('defect')" />
    <IssueFormDialog v-model="issueOpen" :preset="issuePreset" @saved="onSaved('issue')" />
  </div>
</template>
