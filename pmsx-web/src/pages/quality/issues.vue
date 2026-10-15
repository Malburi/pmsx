<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { addIssueCommentApi, deleteIssueApi, escalateIssueApi, getIssueApi, getIssuesApi } from '@/api/qualityApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import IssueFormDialog from '@/components/quality/IssueFormDialog.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import type { Issue, IssueStatus } from '@/types/r2'
import { fitColumns } from '@/utils/columns'
import { today } from '@/utils/date'
import { formatDateTime } from '@/utils/format'
import { ISSUE_EDIT_ROLES } from '@/utils/permission'
import { ISSUE_STATUSES } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-QA-001 이슈 (FR-QA-001) — 등록·조치 이력(코멘트)·에스컬레이션
const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()

const page = ref<Page<Issue> | null>(null)
const options = ref({ page: 1, itemsPerPage: 20 })
const filter = reactive({ q: '', status: null as IssueStatus | null, openOnly: true })
const loading = ref(false)
const error = ref<string | null>(null)
const detail = ref<Issue | null>(null)
const comment = ref('')
const posting = ref(false)
const form = reactive({ open: false, issue: null as Issue | null })
const removeOpen = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    page.value = await getIssuesApi(projectId.value, {
      q: filter.q.trim() || undefined, status: filter.status ?? undefined, page: options.value.page - 1, size: options.value.itemsPerPage,
    })
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const open = async (issueId: string) => {
  try {
    detail.value = await getIssueApi(issueId)
  } catch {
    showErrorToast('common.error.unknown')
  }
}

const onOptions = (o: { page: number; itemsPerPage: number }) => { options.value = { page: o.page, itemsPerPage: o.itemsPerPage }; void load() }
const onRowClick = (_e: Event, p: { item: Issue }) => { void open(p.item.issueId) }

const postComment = async () => {
  if (!detail.value || !comment.value.trim()) return
  posting.value = true
  try {
    const c = await addIssueCommentApi(detail.value.issueId, comment.value.trim())
    detail.value.comments = [...(detail.value.comments ?? []), c]
    comment.value = ''
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    posting.value = false
  }
}

const escalate = async () => {
  if (!detail.value) return
  try {
    detail.value = { ...(await escalateIssueApi(detail.value.issueId)), comments: detail.value.comments }
    showSuccessToast('qa.issue.escalated')
    void load()
  } catch (e) {
    showErrorToast((e as ApiError).code === 'CONFLICT' ? 'qa.issue.escalateMax' : 'common.actionFailed')
  }
}

const onSaved = async (i: Issue) => {
  await load()
  await open(i.issueId)
}

const remove = async () => {
  if (!detail.value) return
  try {
    await deleteIssueApi(detail.value.issueId)
    detail.value = null
    removeOpen.value = false
    void load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const overdue = (i: Issue) => !!i.dueDate && (i.status === 'OPEN' || i.status === 'IN_PROGRESS') && i.dueDate < today()

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(ISSUE_EDIT_ROLES))
const statusItems = computed(() => ISSUE_STATUSES.map((s) => ({ title: t(`qa.issueStatus.${s}`), value: s })))
const rows = computed(() => (page.value?.content ?? []).filter((i) => !filter.openOnly || filter.status || i.status === 'OPEN' || i.status === 'IN_PROGRESS'))
// 2단 화면에서 목록 자리가 좁으면 우선순위 낮은 열을 숨긴다 (마지막 열 잘림 방지)
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 700)
const allHeaders = computed(() => [
  { title: t('qa.col.no'), key: 'issueNo', width: 90 },
  { title: t('qa.col.title'), key: 'title', minWidth: 220 },
  { title: t('qa.col.priority'), key: 'priority', width: 70, hideBelow: 560 },
  { title: t('qa.col.status'), key: 'status', width: 90 },
  { title: t('qa.col.owner'), key: 'owner', width: 90, hideBelow: 720 },
  { title: t('qa.col.due'), key: 'dueDate', width: 100, hideBelow: 640 },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))

// ─────────── watch ───────────
watch(() => [filter.status], () => { options.value.page = 1; void load() })
watch(() => filter.q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { options.value.page = 1; void load() }, 300)
})
watch(() => route.query.id, (id) => { if (typeof id === 'string') void open(id) }, { immediate: true })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.quality.issue')" :subtitle="t('qa.issue.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton dataset-id="issues" :file-name="t('nav.quality.issue')" :params="{ projectId }" />
        <VBtn v-if="canEdit" color="primary" size="small" prepend-icon="ri-add-line" data-test="issue-add" @click="Object.assign(form, { open: true, issue: null })">{{ t('qa.issue.add') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="7">
        <VCard ref="listCard" border flat>
          <VCardText class="pb-0">
            <VRow dense align="center">
              <VCol cols="12" sm="5"><VTextField v-model="filter.q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
              <VCol cols="7" sm="4"><VSelect v-model="filter.status" :items="statusItems" :label="t('qa.col.status')" clearable hide-details /></VCol>
              <VCol cols="5" sm="3"><VCheckbox v-model="filter.openOnly" :label="t('qa.issue.openOnly')" :disabled="!!filter.status" density="compact" hide-details /></VCol>
            </VRow>
          </VCardText>
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VDataTableServer
            :headers="headers"
            :items="rows"
            :items-length="page?.totalElements ?? 0"
            :loading="loading"
            :page="options.page"
            :items-per-page="options.itemsPerPage"
            item-value="issueId"
            density="compact"
            hover
            :row-props="({ item }) => ({ class: item.issueId === detail?.issueId ? 'row-selected' : '', tabindex: 0 })"
            data-test="issue-table"
            @update:options="onOptions"
            @click:row="onRowClick"
          >
            <template #item.issueNo="{ item }"><span class="font-mono">{{ item.issueNo }}</span></template>
            <template #item.title="{ item }">
              {{ item.title }}
              <VChip v-if="item.escalation" size="x-small" color="error" variant="flat" label class="ml-1">{{ t('qa.issue.escLevel', { n: item.escalation }) }}</VChip>
              <VIcon v-if="item.elementId" icon="ri-cursor-line" size="13" class="ml-1" :title="t('qa.fromHotspot')" />
            </template>
            <template #item.priority="{ item }"><AppStatusChip :status="item.priority" prefix="qa.priority" size="x-small" /></template>
            <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="qa.issueStatus" size="x-small" /></template>
            <template #item.owner="{ item }">{{ item.owner?.name ?? '-' }}</template>
            <template #item.dueDate="{ item }"><span :class="['font-mono text-caption', { 'text-error font-weight-bold': overdue(item) }]">{{ item.dueDate ?? '-' }}</span></template>
          </VDataTableServer>
        </VCard>
      </VCol>
      <VCol cols="12" lg="5">
        <VCard v-if="detail" border flat data-test="issue-detail">
          <VCardTitle class="d-flex align-center ga-2">
            <span class="font-mono text-body-2">{{ detail.issueNo }}</span>
            <AppStatusChip :status="detail.status" prefix="qa.issueStatus" size="x-small" />
            <AppStatusChip :status="detail.priority" prefix="qa.priority" size="x-small" />
            <VSpacer />
            <template v-if="canEdit">
              <VBtn size="small" variant="tonal" color="error" prepend-icon="ri-alarm-warning-line" :disabled="detail.escalation >= 2" data-test="issue-escalate" @click="escalate">
                {{ t('qa.issue.escalate') }}
              </VBtn>
              <VBtn icon variant="text" size="small" :aria-label="t('common.edit')" @click="Object.assign(form, { open: true, issue: detail })"><VIcon icon="ri-edit-line" /></VBtn>
              <VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="removeOpen = true"><VIcon icon="ri-delete-bin-line" /></VBtn>
            </template>
          </VCardTitle>
          <VCardText>
            <div class="text-subtitle-1 font-weight-medium">{{ detail.title }}</div>
            <div class="text-caption text-muted mb-2">
              {{ t('qa.col.owner') }} {{ detail.owner?.name ?? '-' }} · {{ t('qa.col.due') }} {{ detail.dueDate ?? '-' }}
              <span v-if="detail.escalation"> · <b class="text-error">{{ t('qa.issue.escLevel', { n: detail.escalation }) }}</b></span>
            </div>
            <p class="text-body-2 pre mb-4">{{ detail.description }}</p>
            <div class="text-subtitle-2 mb-2">{{ t('qa.issue.actions') }}</div>
            <VTimeline density="compact" side="end" truncate-line="both">
              <VTimelineItem v-for="c in detail.comments ?? []" :key="c.commentId" size="x-small" dot-color="primary">
                <div class="text-caption text-muted">{{ c.author.name }} · {{ formatDateTime(c.createdAt) }}</div>
                <div class="text-body-2 pre">{{ c.content }}</div>
              </VTimelineItem>
            </VTimeline>
            <div v-if="!(detail.comments ?? []).length" class="text-body-2 text-muted mb-2">{{ t('qa.issue.noActions') }}</div>
            <div v-if="canEdit" class="d-flex ga-2 mt-2">
              <VTextField v-model="comment" :label="t('qa.issue.addAction')" hide-details data-test="issue-comment" @keyup.enter="postComment" />
              <VBtn color="primary" :loading="posting" :disabled="!comment.trim()" @click="postComment">{{ t('common.add') }}</VBtn>
            </div>
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('qa.issue.pick') }}</VAlert>
      </VCol>
    </VRow>
    <IssueFormDialog v-model="form.open" :issue="form.issue" @saved="onSaved" />
    <ConfirmDialog v-model="removeOpen" :title="t('common.delete')" :message="t('qa.issue.removeMessage', { no: detail?.issueNo ?? '' })" @confirm="remove" />
  </VContainer>
</template>

<style scoped>
.pre {
  white-space: pre-wrap;
}

:deep(.row-selected) {
  background: rgba(var(--v-theme-primary), 0.08);
}
</style>
