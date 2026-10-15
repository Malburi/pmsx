<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { addSrCommentApi, getSrApi, getSrsApi, srToKbApi, transitionSrApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import AttachmentPanel from '@/components/common/AttachmentPanel.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import UserLookupField from '@/components/common/UserLookupField.vue'
import SlaBadge from '@/components/sm/SlaBadge.vue'
import SrFormDialog from '@/components/sm/SrFormDialog.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useNow } from '@/composables/useNow'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type { ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import { SR_PRIORITIES, SR_STATUSES, SR_TYPES, type Sr, type SrPriority, type SrStatus, type SrType } from '@/types/r3'
import { fitColumns, isColumnHidden } from '@/utils/columns'
import { formatDateTime } from '@/utils/format'
import { canProcessSr, SR_ASSIGN_ROLES, SR_CREATE_ROLES } from '@/utils/permission'
import { allowedSrTransitions, srTransitionNeedsAssignee, srTransitionNeedsComment } from '@/utils/sm'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-001 SR 목록 / 처리 (FR-SM-001~003·005·011)
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectStore = useProjectStore()
const now = useNow(30_000)

const page = ref<Page<Sr> | null>(null)
const fetchedAt = ref(Date.now())
const options = ref({ page: 1, itemsPerPage: 20 })
const filter = reactive({
  q: '', status: null as SrStatus | null, type: null as SrType | null, priority: null as SrPriority | null,
  slaState: null as 'DUE_SOON' | 'BREACHED' | null, mine: false, openOnly: true,
})
const loading = ref(false)
const error = ref<string | null>(null)
const detail = ref<Sr | null>(null)
const detailFetchedAt = ref(Date.now())
const comment = reactive({ text: '', internal: false, posting: false })
const form = reactive({ open: false, sr: null as Sr | null })
const transition = reactive({ open: false, to: null as SrStatus | null, comment: '', assigneeId: null as string | null, saving: false, error: null as string | null })
const kbBusy = ref(false)
const historyOpen = ref(false)
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 760)
let searchTimer: ReturnType<typeof setTimeout> | null = null
let pollTimer: ReturnType<typeof setInterval> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    page.value = await getSrsApi(projectId.value, {
      q: filter.q.trim() || undefined, status: filter.status ?? undefined, type: filter.type ?? undefined,
      priority: filter.priority ?? undefined, slaState: filter.slaState ?? undefined,
      assigneeId: filter.mine ? auth.userId ?? undefined : undefined,
      page: options.value.page - 1, size: options.value.itemsPerPage,
    })
    fetchedAt.value = Date.now()
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => { detail.value = null; void load() })

const open = async (srId: string) => {
  try {
    detail.value = await getSrApi(srId)
    detailFetchedAt.value = Date.now()
    if (route.query.id !== srId) void router.replace({ query: { ...route.query, id: srId } })
  } catch {
    showErrorToast('common.error.unknown')
  }
}

const onOptions = (o: { page: number; itemsPerPage: number }) => { options.value = { page: o.page, itemsPerPage: o.itemsPerPage }; void load() }
const onRowClick = (_e: Event, p: { item: Sr }) => { void open(p.item.srId) }

const openTransition = (to: SrStatus) => {
  Object.assign(transition, { open: true, to, comment: '', assigneeId: detail.value?.assignee?.userId ?? null, saving: false, error: null })
}

const submitTransition = async () => {
  if (!detail.value || !transition.to) return
  const from = detail.value.status
  if (srTransitionNeedsComment(from, transition.to) && !transition.comment.trim()) {
    transition.error = t('sm.sr.commentRequired')
    return
  }
  if (srTransitionNeedsAssignee(transition.to) && !transition.assigneeId) {
    transition.error = t('sm.sr.assigneeRequired')
    return
  }
  transition.saving = true
  try {
    const updated = await transitionSrApi(detail.value.srId, {
      to: transition.to, comment: transition.comment.trim() || undefined, assigneeId: transition.assigneeId ?? undefined,
    })
    detail.value = { ...detail.value, ...updated }
    detailFetchedAt.value = Date.now()
    transition.open = false
    showSuccessToast('sm.sr.transitioned')
    void load()
    void open(updated.srId)
  } catch (e) {
    transition.error = (e as ApiError).message ?? t('common.actionFailed')
  } finally {
    transition.saving = false
  }
}

const postComment = async () => {
  if (!detail.value || !comment.text.trim()) return
  comment.posting = true
  try {
    const c = await addSrCommentApi(detail.value.srId, comment.text.trim(), comment.internal)
    detail.value.comments = [...(detail.value.comments ?? []), c]
    Object.assign(comment, { text: '', internal: false })
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    comment.posting = false
  }
}

const toKb = async () => {
  if (!detail.value) return
  kbBusy.value = true
  try {
    const kb = await srToKbApi(detail.value.srId)
    showSuccessToast('sm.sr.kbCreated')
    await router.push({ path: '/sm/kb', query: { id: kb.kbId, edit: '1' } })
  } catch {
    showErrorToast('common.actionFailed')
  } finally {
    kbBusy.value = false
  }
}

const onSaved = async (sr: Sr) => {
  await load()
  await open(sr.srId)
}

const viewerLink = (sr: Sr) =>
  sr.systemId && sr.screenId ? { path: `/asis/viewer/${sr.systemId}/${sr.screenId}`, query: sr.elementId ? { el: sr.elementId } : {} } : null

const similarLink = (s: NonNullable<Sr['similar']>[number]) =>
  s.kind === 'KB' ? { path: '/sm/kb', query: { id: s.id } } : { path: '/sm/srs', query: { id: s.id } }

// ─────────── computed ───────────
const canCreate = computed(() => auth.hasAnyRole(SR_CREATE_ROLES))
const canAssign = computed(() => auth.hasAnyRole(SR_ASSIGN_ROLES))
const canProcess = computed(() => !!detail.value && canProcessSr((r) => auth.hasAnyRole(r), detail.value.assignee?.userId ?? null, auth.userId))
const transitions = computed(() => (detail.value ? allowedSrTransitions(detail.value.status) : []))
const canToKb = computed(() => !!detail.value && canProcess.value && ['RESOLVED', 'CONFIRMED', 'CLOSED'].includes(detail.value.status))
const statusItems = computed(() => SR_STATUSES.map((s) => ({ title: t(`sm.srStatus.${s}`), value: s })))
const typeItems = computed(() => SR_TYPES.map((s) => ({ title: t(`sm.srType.${s}`), value: s })))
const priorityItems = computed(() => SR_PRIORITIES.map((s) => ({ title: t(`sm.priority.${s}`), value: s })))
const slaItems = computed(() => (['DUE_SOON', 'BREACHED'] as const).map((s) => ({ title: t(`sm.slaState.${s}`), value: s })))
const rows = computed(() => (page.value?.content ?? []).filter((s) => !filter.openOnly || filter.status || !['CLOSED', 'REJECTED'].includes(s.status)))
// 목록 자리가 좁으면(2단 화면, 1280px 이하) 담당자·우선순위 열을 숨기고 우선순위는 제목 아래에 같이 보여 준다
const allHeaders = computed(() => [
  { title: t('sm.col.no'), key: 'srNo', width: 92 },
  { title: t('sm.col.title'), key: 'title' },
  { title: t('sm.col.priority'), key: 'priority', width: 76, hideBelow: 860 },
  { title: t('sm.col.status'), key: 'status', width: 88 },
  { title: t('sm.col.assignee'), key: 'assignee', width: 84, hideBelow: 800 },
  { title: t('sm.col.sla'), key: 'sla', width: 168, sortable: false },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))
const priorityHidden = computed(() => isColumnHidden(allHeaders.value, listWidth.value, 'priority'))
// 제목 칸에 남는 너비 — 나머지 열 너비 합 + 칸 여백을 뺀 값
const titleMax = computed(() => {
  const others = headers.value.filter((h) => h.key !== 'title').reduce((sum, h) => sum + ((h as { width?: number }).width ?? 0) + 32, 0)
  return Math.max(140, listWidth.value - others - 48)
})
const transitionNeedsComment = computed(() => !!detail.value && !!transition.to && srTransitionNeedsComment(detail.value.status, transition.to))

// ─────────── watch ───────────
watch(() => [filter.status, filter.type, filter.priority, filter.slaState, filter.mine], () => { options.value.page = 1; void load() })
watch(() => filter.q, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { options.value.page = 1; void load() }, 300)
})
watch(() => route.query.id, (id) => { if (typeof id === 'string' && id !== detail.value?.srId) void open(id) }, { immediate: true })
watch(() => route.query.sla, (s) => { if (s === 'DUE_SOON' || s === 'BREACHED') filter.slaState = s }, { immediate: true })

// ─────────── 라이프사이클 ───────────
// SLA 상태가 바뀌는 것을 반영하도록 1분마다 목록을 다시 읽는다 (업무시간 보정은 서버 계산)
onMounted(() => { pollTimer = setInterval(() => { void load() }, 60_000) })
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.sr')" :subtitle="t('sm.sr.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton dataset-id="srs" :file-name="t('nav.sm.sr')" :params="{ projectId }" />
        <VBtn v-if="canCreate" color="primary" size="small" prepend-icon="ri-add-line" data-test="sr-add" @click="Object.assign(form, { open: true, sr: null })">{{ t('sm.sr.create') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="7">
        <VCard ref="listCard" border flat>
          <VCardText class="pb-0">
            <VRow dense align="center">
              <VCol cols="12" sm="6" lg="12"><VTextField v-model="filter.q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details data-test="sr-search" /></VCol>
              <VCol cols="6" sm="3"><VSelect v-model="filter.status" :items="statusItems" :label="t('sm.col.status')" clearable hide-details /></VCol>
              <VCol cols="6" sm="3"><VSelect v-model="filter.type" :items="typeItems" :label="t('sm.col.type')" clearable hide-details /></VCol>
              <VCol cols="6" sm="3"><VSelect v-model="filter.priority" :items="priorityItems" :label="t('sm.col.priority')" clearable hide-details /></VCol>
              <VCol cols="6" sm="3"><VSelect v-model="filter.slaState" :items="slaItems" :label="t('sm.col.sla')" clearable hide-details data-test="sr-sla-filter" /></VCol>
              <VCol cols="6" sm="3"><VCheckbox v-model="filter.openOnly" :label="t('sm.sr.openOnly')" :disabled="!!filter.status" density="compact" hide-details /></VCol>
              <VCol cols="6" sm="3"><VCheckbox v-model="filter.mine" :label="t('sm.sr.mine')" density="compact" hide-details /></VCol>
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
            item-value="srId"
            density="compact"
            hover
            class="sr-table"
            :row-props="({ item }) => ({ class: item.srId === detail?.srId ? 'row-selected' : '', tabindex: 0 })"
            data-test="sr-table"
            @update:options="onOptions"
            @click:row="onRowClick"
          >
            <template #item.srNo="{ item }"><span class="font-mono">{{ item.srNo }}</span></template>
            <template #item.title="{ item }">
              <div class="d-flex align-center ga-1 title-cell" :style="{ maxWidth: `${titleMax}px` }">
                <VIcon v-if="item.channel === 'PORTAL'" icon="ri-global-line" size="13" :title="t('sm.channel.PORTAL')" />
                <VIcon v-else-if="item.channel === 'EMAIL'" icon="ri-mail-line" size="13" :title="t('sm.channel.EMAIL')" />
                <span class="text-truncate">{{ item.title }}</span>
              </div>
              <div class="d-flex align-center ga-1 text-caption text-muted title-cell" :style="{ maxWidth: `${titleMax}px` }">
                <AppStatusChip v-if="priorityHidden" :status="item.priority" prefix="sm.priority" size="x-small" />
                <span class="text-truncate">{{ t(`sm.srType.${item.type}`) }}<template v-if="item.screenName"> · {{ item.screenName }}</template></span>
              </div>
            </template>
            <template #item.priority="{ item }"><AppStatusChip :status="item.priority" prefix="sm.priority" size="x-small" /></template>
            <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="sm.srStatus" size="x-small" /></template>
            <template #item.assignee="{ item }">{{ item.assignee?.name ?? '-' }}</template>
            <template #item.sla="{ item }"><SlaBadge :sla="item.sla" :fetched-at="fetchedAt" :now="now" size="x-small" /></template>
          </VDataTableServer>
        </VCard>
      </VCol>

      <VCol cols="12" lg="5">
        <VCard v-if="detail" border flat data-test="sr-detail">
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            <span class="font-mono text-body-2">{{ detail.srNo }}</span>
            <AppStatusChip :status="detail.status" prefix="sm.srStatus" size="x-small" />
            <AppStatusChip :status="detail.priority" prefix="sm.priority" size="x-small" />
            <VChip size="x-small" variant="outlined" label>{{ t(`sm.srType.${detail.type}`) }}</VChip>
            <VSpacer />
            <VBtn v-if="canToKb" size="small" variant="tonal" prepend-icon="ri-book-2-line" :loading="kbBusy" data-test="sr-to-kb" @click="toKb">{{ t('sm.sr.toKb') }}</VBtn>
            <VBtn v-if="canProcess" icon variant="text" size="small" :aria-label="t('common.edit')" @click="Object.assign(form, { open: true, sr: detail })"><VIcon icon="ri-edit-line" /></VBtn>
          </VCardTitle>
          <VCardText>
            <div class="text-subtitle-1 font-weight-medium">{{ detail.title }}</div>
            <div class="text-caption text-muted mb-2">
              {{ t('sm.sr.requester') }} {{ detail.requester.name }}<template v-if="detail.requester.org"> ({{ detail.requester.org }})</template>
              · {{ t(`sm.channel.${detail.channel}`) }} · {{ formatDateTime(detail.createdAt) }}
            </div>
            <div class="sla-box mb-3 pa-2 rounded border">
              <div class="d-flex flex-wrap align-center ga-2">
                <SlaBadge :sla="detail.sla" :fetched-at="detailFetchedAt" :now="now" />
                <VSpacer />
                <span v-if="detail.sla" class="text-caption text-muted">{{ detail.sla.policyName }}</span>
              </div>
              <div v-if="detail.sla" class="text-caption text-muted mt-1 font-mono">
                {{ t('sm.sla.responseDue') }} {{ formatDateTime(detail.sla.responseDue) }}<span v-if="detail.sla.respondedAt"> ✓</span>
                · {{ t('sm.sla.resolveDue') }} {{ formatDateTime(detail.sla.resolveDue) }}
                <span v-if="detail.sla.pausedMinutes"> · {{ t('sm.sla.pausedFor', { n: detail.sla.pausedMinutes }) }}</span>
              </div>
            </div>
            <p class="text-body-2 pre mb-3">{{ detail.description }}</p>

            <!-- 대상 화면 → 뷰어 딥링크 (FR-SM-003) -->
            <div class="d-flex flex-wrap align-center ga-2 mb-3">
              <VIcon icon="ri-window-line" size="16" />
              <span class="text-body-2">{{ detail.screenName ?? t('sm.sr.targetScreenNone') }}</span>
              <VBtn
                v-if="viewerLink(detail)"
                :to="viewerLink(detail)!"
                size="small"
                variant="tonal"
                color="primary"
                prepend-icon="ri-eye-line"
                data-test="sr-open-viewer"
              >{{ t('sm.sr.openViewer') }}</VBtn>
            </div>

            <!-- 상태 전이 -->
            <div v-if="canProcess && transitions.length" class="d-flex flex-wrap ga-2 mb-4" data-test="sr-transitions">
              <VBtn
                v-for="to in transitions"
                :key="to"
                size="small"
                :variant="to === 'REJECTED' ? 'text' : 'tonal'"
                :color="to === 'REJECTED' ? 'error' : 'primary'"
                :data-test="`sr-to-${to}`"
                @click="openTransition(to)"
              >{{ t(`sm.srAction.${to}`) }}</VBtn>
            </div>

            <div class="d-flex align-center ga-2 mb-2">
              <span class="text-subtitle-2">{{ t('sm.sr.effort') }}</span>
              <span class="font-mono">{{ detail.effortHours }}h</span>
              <VBtn v-if="canProcess" size="x-small" variant="text" prepend-icon="ri-time-line" to="/resource/timesheet">{{ t('sm.sr.logEffort') }}</VBtn>
            </div>

            <!-- 유사 KB / SR -->
            <template v-if="detail.similar?.length">
              <div class="text-subtitle-2 mb-1">{{ t('sm.sr.similar') }}</div>
              <VList density="compact" class="py-0 mb-3 border rounded-lg" data-test="sr-similar">
                <VListItem v-for="s in detail.similar" :key="`${s.kind}-${s.id}`" :to="similarLink(s)" :active="false">
                  <template #prepend><VChip size="x-small" :color="s.kind === 'KB' ? 'success' : 'secondary'" variant="tonal" label class="mr-2">{{ s.kind }}</VChip></template>
                  <VListItemTitle class="text-body-2"><span class="font-mono text-caption mr-1">{{ s.no }}</span>{{ s.title }}</VListItemTitle>
                  <template #append><span class="text-caption text-muted">{{ Math.round(s.score * 100) }}%</span></template>
                </VListItem>
              </VList>
            </template>

            <div class="text-subtitle-2 mb-2">{{ t('sm.sr.comments') }}</div>
            <VTimeline density="compact" side="end" truncate-line="both">
              <VTimelineItem v-for="c in detail.comments ?? []" :key="c.commentId" size="x-small" :dot-color="c.internal ? 'warning' : 'primary'">
                <div class="text-caption text-muted">
                  {{ c.author.name }} · {{ formatDateTime(c.createdAt) }}
                  <VChip v-if="c.internal" size="x-small" color="warning" variant="tonal" label class="ml-1">{{ t('sm.sr.internal') }}</VChip>
                </div>
                <div class="text-body-2 pre">{{ c.content }}</div>
              </VTimelineItem>
            </VTimeline>
            <div v-if="!(detail.comments ?? []).length" class="text-body-2 text-muted mb-2">{{ t('sm.sr.noComments') }}</div>
            <div v-if="canProcess" class="mt-2">
              <VTextarea v-model="comment.text" :label="t('sm.sr.addComment')" rows="2" auto-grow hide-details data-test="sr-comment" />
              <div class="d-flex align-center ga-2 mt-1">
                <VCheckbox v-model="comment.internal" :label="t('sm.sr.internalHint')" density="compact" hide-details />
                <VSpacer />
                <VBtn color="primary" size="small" :loading="comment.posting" :disabled="!comment.text.trim()" data-test="sr-comment-post" @click="postComment">{{ t('common.add') }}</VBtn>
              </div>
            </div>

            <VExpansionPanels v-model="historyOpen" class="mt-4" variant="accordion" flat>
              <VExpansionPanel :value="true" :title="t('sm.sr.history')" class="border">
                <VExpansionPanelText>
                  <div v-for="(h, i) in detail.history ?? []" :key="i" class="text-caption mb-1">
                    <span class="font-mono text-muted">{{ formatDateTime(h.at) }}</span> · {{ h.user }} ·
                    <template v-if="h.from">{{ t(`sm.srStatus.${h.from}`) }} → </template>{{ t(`sm.srStatus.${h.to}`) }}
                    <span v-if="h.comment" class="text-muted"> — {{ h.comment }}</span>
                  </div>
                  <div v-if="!(detail.history ?? []).length" class="text-caption text-muted">{{ t('common.noData') }}</div>
                </VExpansionPanelText>
              </VExpansionPanel>
            </VExpansionPanels>

            <div class="text-subtitle-2 mt-4 mb-2">{{ t('sm.sr.attachments') }}</div>
            <AttachmentPanel target-type="SM_SR" :target-id="detail.srId" :readonly="!canProcess" />
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('sm.sr.pick') }}</VAlert>
      </VCol>
    </VRow>

    <SrFormDialog v-model="form.open" :project-id="projectId" :sr="form.sr" :systems="projectStore.systems" :can-assign="canAssign" @saved="onSaved" />

    <VDialog v-model="transition.open" max-width="460">
      <VCard :title="transition.to ? t(`sm.srAction.${transition.to}`) : ''">
        <VCardText>
          <VAlert v-if="transition.error" type="error" variant="tonal" density="compact" class="mb-3">{{ transition.error }}</VAlert>
          <div v-if="detail && transition.to" class="text-body-2 mb-3">
            {{ t(`sm.srStatus.${detail.status}`) }} → <b>{{ t(`sm.srStatus.${transition.to}`) }}</b>
          </div>
          <UserLookupField
            v-if="transition.to === 'ASSIGNED'"
            v-model="transition.assigneeId"
            :label="t('sm.col.assignee')"
            :initial="detail?.assignee ?? null"
            class="mb-3"
            data-test="sr-transition-assignee"
          />
          <VTextarea
            v-model="transition.comment"
            :label="transitionNeedsComment ? t('sm.sr.reasonRequired') : t('sm.sr.commentOptional')"
            rows="3"
            auto-grow
            data-test="sr-transition-comment"
          />
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="transition.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn :color="transition.to === 'REJECTED' ? 'error' : 'primary'" :loading="transition.saving" data-test="sr-transition-ok" @click="submitTransition">{{ t('common.confirm') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.pre {
  white-space: pre-wrap;
}

.title-cell {
  min-width: 0;
}

/* 열 수는 목록 자리 너비에 맞춰 줄이고(headers), 칸 안 줄바꿈은 막는다. 아주 좁은 화면(모바일)만 표 안에서 가로 스크롤 */
.sr-table :deep(table) {
  min-width: 520px;
}

.sr-table :deep(th),
.sr-table :deep(td) {
  white-space: nowrap;
}

.sla-box {
  border-color: rgb(var(--v-theme-line)) !important;
}

:deep(.row-selected) {
  background: rgba(var(--v-theme-primary), 0.08);
}
</style>
