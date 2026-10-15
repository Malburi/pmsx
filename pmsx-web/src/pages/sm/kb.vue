<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { createKbApi, deleteKbApi, getKbApi, getKbListApi, updateKbApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MarkdownEditor from '@/components/common/MarkdownEditor.vue'
import MarkdownView from '@/components/common/MarkdownView.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type { ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import type { KbArticle, KbPayload } from '@/types/r3'
import { formatDateTime } from '@/utils/format'
import { SM_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-007 KB (FR-SM-011) — 처리 완료 SR 을 지식으로 남기고 검색한다 (Markdown)
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectStore = useProjectStore()

const page = ref<Page<KbArticle> | null>(null)
const q = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const detail = ref<KbArticle | null>(null)
const edit = reactive({ on: false, data: {} as KbPayload, tagsText: '', saving: false, error: null as string | null })
const removeOpen = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    page.value = await getKbListApi(projectId.value, { q: q.value.trim() || undefined, page: 0, size: 100 })
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const open = async (kbId: string, startEdit = false) => {
  try {
    detail.value = await getKbApi(kbId)
    edit.on = false
    if (startEdit) startEditing(detail.value)
    if (route.query.id !== kbId) void router.replace({ query: { id: kbId } })
  } catch {
    showErrorToast('common.error.unknown')
  }
}

const startEditing = (kb: KbArticle | null) => {
  edit.data = kb
    ? { title: kb.title, content: kb.content, tags: [...kb.tags], systemId: kb.systemId, screenId: kb.screenId, sourceSrId: kb.sourceSrId }
    : { title: '', content: '', tags: [], systemId: null, screenId: null, sourceSrId: null }
  edit.tagsText = edit.data.tags.join(', ')
  Object.assign(edit, { on: true, saving: false, error: null })
  if (!kb) detail.value = null
}

const save = async () => {
  if (!projectId.value) return
  if (!edit.data.title.trim() || !edit.data.content.trim()) { edit.error = t('validation.required'); return }
  edit.saving = true
  try {
    const payload: KbPayload = { ...edit.data, tags: edit.tagsText.split(',').map((s) => s.trim()).filter(Boolean) }
    const saved = detail.value ? await updateKbApi(detail.value.kbId, payload) : await createKbApi(projectId.value, payload)
    detail.value = saved
    edit.on = false
    showSuccessToast('common.saved')
    void router.replace({ query: { id: saved.kbId } })
    void load()
  } catch (e) {
    edit.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    edit.saving = false
  }
}

const remove = async () => {
  if (!detail.value) return
  try {
    await deleteKbApi(detail.value.kbId)
    detail.value = null
    removeOpen.value = false
    void router.replace({ query: {} })
    void load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SM_EDIT_ROLES))
const items = computed(() => page.value?.content ?? [])
const systemName = (id: string | null) => projectStore.systems.find((s) => s.systemId === id)?.name ?? null

// ─────────── watch ───────────
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { void load() }, 300)
})
watch(() => route.query.id, (id) => {
  if (typeof id === 'string' && id !== detail.value?.kbId) void open(id, route.query.edit === '1')
}, { immediate: true })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.kb')" :subtitle="t('sm.kb.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" data-test="kb-add" @click="startEditing(null)">{{ t('sm.kb.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" md="5" lg="4">
        <VCard border flat>
          <VCardText class="pb-2"><VTextField v-model="q" :label="t('sm.kb.search')" prepend-inner-icon="ri-search-line" clearable hide-details data-test="kb-search" /></VCardText>
          <VProgressLinear v-if="loading" indeterminate color="primary" />
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VList density="compact" nav data-test="kb-list">
            <VListItem v-for="k in items" :key="k.kbId" :active="k.kbId === detail?.kbId" color="primary" @click="open(k.kbId)">
              <VListItemTitle>{{ k.title }}</VListItemTitle>
              <VListItemSubtitle>
                <span class="font-mono">{{ k.kbNo }}</span>
                <VChip v-for="tag in k.tags.slice(0, 3)" :key="tag" size="x-small" variant="tonal" class="ml-1">{{ tag }}</VChip>
              </VListItemSubtitle>
            </VListItem>
            <VListItem v-if="!items.length && !loading" :title="t('common.noData')" disabled />
          </VList>
        </VCard>
      </VCol>
      <VCol cols="12" md="7" lg="8">
        <VCard v-if="edit.on" border flat :title="detail ? t('sm.kb.edit', { no: detail.kbNo }) : t('sm.kb.add')">
          <VCardText>
            <VAlert v-if="edit.error" type="error" variant="tonal" density="compact" class="mb-3">{{ edit.error }}</VAlert>
            <VTextField v-model="edit.data.title" :label="t('sm.col.title')" class="mb-2" data-test="kb-title" />
            <VTextField v-model="edit.tagsText" :label="t('sm.kb.tags')" :hint="t('sm.kb.tagsHint')" persistent-hint class="mb-3" />
            <MarkdownEditor v-model="edit.data.content" :placeholder="t('sm.kb.contentPlaceholder')" :max-length="20000" />
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="edit.on = false">{{ t('common.cancel') }}</VBtn>
            <VBtn color="primary" :loading="edit.saving" data-test="kb-save" @click="save">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VCard>
        <VCard v-else-if="detail" border flat data-test="kb-detail">
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            <span class="font-mono text-body-2">{{ detail.kbNo }}</span>
            <span class="text-truncate">{{ detail.title }}</span>
            <VSpacer />
            <template v-if="canEdit">
              <VBtn icon variant="text" size="small" :aria-label="t('common.edit')" @click="startEditing(detail)"><VIcon icon="ri-edit-line" /></VBtn>
              <VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="removeOpen = true"><VIcon icon="ri-delete-bin-line" /></VBtn>
            </template>
          </VCardTitle>
          <VCardText>
            <div class="text-caption text-muted mb-3">
              {{ t('sm.kb.updatedAt') }} {{ formatDateTime(detail.updatedAt) }}
              <template v-if="systemName(detail.systemId)"> · {{ systemName(detail.systemId) }}</template>
              <template v-if="detail.sourceSrId"> · <RouterLink :to="{ path: '/sm/srs', query: { id: detail.sourceSrId } }">{{ t('sm.kb.sourceSr') }}</RouterLink></template>
              <template v-if="detail.systemId && detail.screenId"> · <RouterLink :to="`/asis/viewer/${detail.systemId}/${detail.screenId}`">{{ t('sm.sr.openViewer') }}</RouterLink></template>
            </div>
            <div class="mb-3"><VChip v-for="tag in detail.tags" :key="tag" size="small" variant="tonal" class="mr-1">{{ tag }}</VChip></div>
            <MarkdownView :source="detail.content" />
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('sm.kb.pick') }}</VAlert>
      </VCol>
    </VRow>
    <ConfirmDialog v-model="removeOpen" :title="t('common.delete')" :message="t('sm.kb.removeMessage', { no: detail?.kbNo ?? '' })" @confirm="remove" />
  </VContainer>
</template>
