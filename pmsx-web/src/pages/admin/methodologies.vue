<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PM]
</route>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createMethodologyApi, deleteMethodologyApi, getMethodologiesApi, updateMethodologyApi } from '@/api/scheduleApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Methodology } from '@/types/r2'
import { METHODOLOGY_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-CM-004 방법론 템플릿 (FR-PJ-002) — 단계 › 태스크(가중치·기간·필수 산출물) 트리를 통째로 편집·저장
const { t } = useI18n()
const auth = useAuthStore()

const list = ref<Methodology[]>([])
const selectedId = ref<string | null>(null)
const draft = ref<Methodology | null>(null)
const savedJson = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const removeOpen = ref(false)

// ─────────── 헬퍼 ───────────
const load = async () => {
  loading.value = true
  error.value = null
  try {
    list.value = await getMethodologiesApi()
    if (!selectedId.value && list.value[0]) pick(list.value[0])
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const pick = (m: Methodology) => {
  selectedId.value = m.methodologyId
  draft.value = structuredClone(m)
  savedJson.value = JSON.stringify(m)
}

const createNew = () => {
  selectedId.value = null
  draft.value = { methodologyId: '', name: t('admin.mtd.newName'), phases: [{ name: t('admin.mtd.newPhase'), order: 1, tasks: [] }] }
  savedJson.value = ''
}

const addPhase = () => draft.value?.phases.push({ name: t('admin.mtd.newPhase'), order: (draft.value?.phases.length ?? 0) + 1, tasks: [] })
const addTask = (pi: number) => draft.value?.phases[pi].tasks.push({ name: t('admin.mtd.newTask'), weight: 1, durationDays: 5, deliverables: [] })
const movePhase = (pi: number, dir: -1 | 1) => {
  const ph = draft.value?.phases
  if (!ph || !ph[pi + dir]) return
  ;[ph[pi], ph[pi + dir]] = [ph[pi + dir], ph[pi]]
  ph.forEach((p, i) => { p.order = i + 1 })
}

const save = async () => {
  if (!draft.value || !draft.value.name.trim()) return
  saving.value = true
  try {
    const { methodologyId, ...body } = draft.value
    body.phases.forEach((p, i) => { p.order = i + 1 })
    const saved = methodologyId ? await updateMethodologyApi(methodologyId, body) : await createMethodologyApi(body)
    showSuccessToast('common.saved')
    await load()
    pick(saved)
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const remove = async () => {
  if (!draft.value?.methodologyId) return
  try {
    await deleteMethodologyApi(draft.value.methodologyId)
    removeOpen.value = false
    selectedId.value = null
    draft.value = null
    await load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(METHODOLOGY_EDIT_ROLES))
const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== savedJson.value)
const totalDays = computed(() => draft.value?.phases.reduce((s, p) => s + p.tasks.reduce((a, tk) => a + (Number(tk.durationDays) || 0), 0), 0) ?? 0)

// ─────────── 라이프사이클 ───────────
onMounted(load)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.admin.template')" :subtitle="t('admin.mtd.subtitle')">
      <VBtn v-if="canEdit" size="small" variant="tonal" prepend-icon="ri-add-line" @click="createNew">{{ t('admin.mtd.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
    <VRow>
      <VCol cols="12" md="3">
        <VCard border flat>
          <VList density="compact" nav>
            <VListItem v-for="m in list" :key="m.methodologyId" :active="m.methodologyId === selectedId" color="primary" :title="m.name" :subtitle="t('admin.mtd.phaseCount', { n: m.phases.length })" @click="pick(m)" />
            <VListItem v-if="!list.length && !loading" :title="t('common.noData')" disabled />
          </VList>
        </VCard>
      </VCol>
      <VCol cols="12" md="9">
        <VCard v-if="draft" border flat data-test="mtd-editor">
          <VCardText>
            <div class="d-flex flex-wrap align-center ga-2 mb-4">
              <VTextField v-model="draft.name" :label="t('admin.mtd.name')" :readonly="!canEdit" hide-details class="name-field" />
              <VChip label variant="tonal">{{ t('admin.mtd.totalDays', { n: totalDays }) }}</VChip>
              <VSpacer />
              <template v-if="canEdit">
                <VBtn v-if="draft.methodologyId" variant="text" color="error" size="small" @click="removeOpen = true">{{ t('common.delete') }}</VBtn>
                <VBtn color="primary" size="small" :disabled="!dirty" :loading="saving" data-test="mtd-save" @click="save">{{ t('common.save') }}</VBtn>
              </template>
            </div>
            <VCard v-for="(ph, pi) in draft.phases" :key="pi" border flat class="mb-3 phase">
              <div class="d-flex align-center ga-2 px-3 py-2 border-b">
                <VChip size="small" color="primary" variant="flat" label>{{ pi + 1 }}</VChip>
                <input v-model="ph.name" class="cell-input phase-name" :readonly="!canEdit" :aria-label="t('admin.mtd.phase')">
                <VSpacer />
                <template v-if="canEdit">
                  <VBtn icon variant="text" size="x-small" :aria-label="t('pj.wbs.up')" @click="movePhase(pi, -1)"><VIcon icon="ri-arrow-up-line" /></VBtn>
                  <VBtn icon variant="text" size="x-small" :aria-label="t('pj.wbs.down')" @click="movePhase(pi, 1)"><VIcon icon="ri-arrow-down-line" /></VBtn>
                  <VBtn icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="draft.phases.splice(pi, 1)"><VIcon icon="ri-delete-bin-line" /></VBtn>
                </template>
              </div>
              <VTable density="compact">
                <thead>
                  <tr><th>{{ t('admin.mtd.task') }}</th><th class="num">{{ t('pj.wbs.colWeight') }}</th><th class="num">{{ t('admin.mtd.days') }}</th><th>{{ t('admin.mtd.deliverables') }}</th><th v-if="canEdit" /></tr>
                </thead>
                <tbody>
                  <tr v-for="(tk, ti) in ph.tasks" :key="ti">
                    <td><input v-model="tk.name" class="cell-input" :readonly="!canEdit" :aria-label="t('admin.mtd.task')"></td>
                    <td class="num"><input v-model.number="tk.weight" type="number" min="0" class="cell-input num" :readonly="!canEdit" :aria-label="t('pj.wbs.colWeight')"></td>
                    <td class="num"><input v-model.number="tk.durationDays" type="number" min="1" class="cell-input num" :readonly="!canEdit" :aria-label="t('admin.mtd.days')"></td>
                    <td>
                      <VCombobox v-model="tk.deliverables" multiple chips closable-chips density="compact" hide-details variant="plain" :readonly="!canEdit" :placeholder="t('admin.mtd.deliverablesPh')" />
                    </td>
                    <td v-if="canEdit"><VBtn icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="ph.tasks.splice(ti, 1)"><VIcon icon="ri-close-line" /></VBtn></td>
                  </tr>
                </tbody>
              </VTable>
              <div v-if="canEdit" class="pa-2"><VBtn size="x-small" variant="text" prepend-icon="ri-add-line" @click="addTask(pi)">{{ t('admin.mtd.addTask') }}</VBtn></div>
            </VCard>
            <VBtn v-if="canEdit" variant="tonal" size="small" prepend-icon="ri-add-line" @click="addPhase">{{ t('admin.mtd.addPhase') }}</VBtn>
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('admin.mtd.pick') }}</VAlert>
      </VCol>
    </VRow>
    <ConfirmDialog v-model="removeOpen" :title="t('common.delete')" :message="t('admin.mtd.removeMessage', { name: draft?.name ?? '' })" @confirm="remove" />
  </VContainer>
</template>

<style scoped>
.name-field {
  max-width: 420px;
}

.num {
  width: 90px;
  text-align: right;
}

.cell-input {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 3px 6px;
  font: inherit;
  color: inherit;
  background: transparent;
}

.cell-input:hover:not([readonly]) {
  border-color: rgb(var(--v-theme-line));
}

.cell-input:focus {
  outline: none;
  border-color: rgb(var(--v-theme-primary));
}

.cell-input.num {
  text-align: right;
}

.phase-name {
  font-weight: 600;
  max-width: 280px;
}
</style>
