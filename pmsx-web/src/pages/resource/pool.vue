<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PMO, PM, PL]
</route>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getResourcesApi, updateResourceApi } from '@/api/resourceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import type { Resource, Skill } from '@/types/r2'
import { RESOURCE_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-RS-001 인력 풀 · 스킬 매트릭스 (FR-RS-001·002)
const { t } = useI18n()
const auth = useAuthStore()

const GRADES = ['특급', '고급', '중급', '초급']
const ORG_TYPES = ['OWN', 'PARTNER', 'CUSTOMER']
const tab = ref<'list' | 'matrix'>('list')
const filter = reactive({ q: '', skill: '', orgType: null as string | null, grade: null as string | null })
const options = ref({ page: 1, itemsPerPage: 50 })
const page = ref<Page<Resource> | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const edit = reactive({ open: false, target: null as Resource | null, grade: null as string | null, company: '', skills: [] as Skill[], saving: false })
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  loading.value = true
  error.value = null
  try {
    page.value = await getResourcesApi({
      q: filter.q.trim() || undefined, skill: filter.skill.trim() || undefined, orgType: filter.orgType ?? undefined, grade: filter.grade ?? undefined,
      page: options.value.page - 1, size: options.value.itemsPerPage,
    })
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const onOptions = (o: { page: number; itemsPerPage: number }) => {
  options.value = { page: o.page, itemsPerPage: o.itemsPerPage }
  void load()
}

const openEdit = (r: Resource) => {
  Object.assign(edit, { open: true, target: r, grade: r.grade, company: r.company ?? '', skills: structuredClone(r.skills), saving: false })
}

const addSkill = () => edit.skills.push({ skill: '', level: 3 })

const saveEdit = async () => {
  if (!edit.target) return
  edit.saving = true
  try {
    await updateResourceApi(edit.target.userId, { grade: edit.grade, company: edit.company || null, skills: edit.skills.filter((s) => s.skill.trim()) })
    edit.open = false
    showSuccessToast('common.saved')
    await load()
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    edit.saving = false
  }
}

const levelOf = (r: Resource, skill: string) => r.skills.find((s) => s.skill === skill)?.level ?? 0

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(RESOURCE_EDIT_ROLES))
const rows = computed(() => page.value?.content ?? [])
const skillColumns = computed(() => [...new Set(rows.value.flatMap((r) => r.skills.map((s) => s.skill)))].sort())
const headers = computed(() => [
  { title: t('rs.pool.colName'), key: 'name', minWidth: 110 },
  { title: t('rs.pool.colOrg'), key: 'orgType', width: 90 },
  { title: t('rs.pool.colCompany'), key: 'company', width: 120 },
  { title: t('rs.pool.colGrade'), key: 'grade', width: 70 },
  { title: t('rs.pool.colSkills'), key: 'skills', sortable: false, minWidth: 240 },
  { title: t('rs.pool.colProjects'), key: 'currentProjects', sortable: false, minWidth: 160 },
  { title: '', key: 'actions', sortable: false, width: 48 },
])
const gradeItems = GRADES.map((g) => ({ title: g, value: g }))
const orgItems = computed(() => ORG_TYPES.map((o) => ({ title: t(`rs.org.${o}`), value: o })))

// ─────────── watch ───────────
watch(() => [filter.orgType, filter.grade], () => { options.value.page = 1; void load() })
watch(() => [filter.q, filter.skill], () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { options.value.page = 1; void load() }, 300)
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.resource.pool')" :subtitle="t('rs.pool.subtitle')" />
    <VCard border flat>
      <VCardText>
        <VRow dense>
          <VCol cols="12" sm="6" md="3"><VTextField v-model="filter.q" :label="t('rs.pool.q')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
          <VCol cols="12" sm="6" md="3"><VTextField v-model="filter.skill" :label="t('rs.pool.skill')" prepend-inner-icon="ri-tools-line" clearable hide-details /></VCol>
          <VCol cols="6" md="3"><VSelect v-model="filter.orgType" :items="orgItems" :label="t('rs.pool.colOrg')" clearable hide-details /></VCol>
          <VCol cols="6" md="3"><VSelect v-model="filter.grade" :items="gradeItems" :label="t('rs.pool.colGrade')" clearable hide-details /></VCol>
        </VRow>
      </VCardText>
      <VTabs v-model="tab" density="compact" color="primary" class="border-b">
        <VTab value="list">{{ t('rs.pool.tabList') }}</VTab>
        <VTab value="matrix" data-test="pool-matrix-tab">{{ t('rs.pool.tabMatrix') }}</VTab>
      </VTabs>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
      <VDataTableServer
        v-show="tab === 'list'"
        :headers="headers"
        :items="rows"
        :items-length="page?.totalElements ?? 0"
        :loading="loading"
        :page="options.page"
        :items-per-page="options.itemsPerPage"
        :items-per-page-options="[20, 50, 100]"
        item-value="userId"
        density="compact"
        @update:options="onOptions"
      >
        <template #item.name="{ item }"><span class="font-weight-medium">{{ item.name }}</span></template>
        <template #item.orgType="{ item }"><VChip size="x-small" variant="tonal" label>{{ t(`rs.org.${item.orgType}`) }}</VChip></template>
        <template #item.skills="{ item }">
          <div class="d-flex flex-wrap ga-1 py-1">
            <VChip v-for="s in item.skills" :key="s.skill" size="x-small" variant="outlined">{{ s.skill }} <b class="ml-1">{{ s.level }}</b></VChip>
          </div>
        </template>
        <template #item.currentProjects="{ item }"><span class="text-caption">{{ item.currentProjects.map((p) => `${p.name}(${p.roleId})`).join(', ') }}</span></template>
        <template #item.actions="{ item }">
          <VBtn v-if="canEdit" icon variant="text" size="small" :aria-label="t('common.edit')" @click="openEdit(item)"><VIcon icon="ri-edit-line" /></VBtn>
        </template>
      </VDataTableServer>
      <div v-show="tab === 'matrix'" class="matrix-wrap" data-test="skill-matrix">
        <table class="matrix">
          <thead>
            <tr>
              <th class="sticky">{{ t('rs.pool.colName') }}</th>
              <th v-for="s in skillColumns" :key="s">{{ s }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.userId" :class="{ clickable: canEdit }" :tabindex="canEdit ? 0 : undefined" @click="canEdit && openEdit(r)">
              <td class="sticky">{{ r.name }} <span class="text-caption text-muted">{{ r.grade }}</span></td>
              <td v-for="s in skillColumns" :key="s" class="lv" :class="`lv-${levelOf(r, s)}`" :title="`${r.name} · ${s} ${levelOf(r, s)}`">
                {{ levelOf(r, s) || '' }}
              </td>
            </tr>
          </tbody>
        </table>
        <div class="text-caption text-muted pa-3">{{ t('rs.pool.levelHint') }}</div>
      </div>
    </VCard>

    <VDialog v-model="edit.open" max-width="560">
      <VCard :title="t('rs.pool.editTitle', { name: edit.target?.name ?? '' })">
        <VCardText>
          <VRow dense>
            <VCol cols="12" sm="6"><VSelect v-model="edit.grade" :items="gradeItems" :label="t('rs.pool.colGrade')" /></VCol>
            <VCol cols="12" sm="6"><VTextField v-model="edit.company" :label="t('rs.pool.colCompany')" /></VCol>
          </VRow>
          <div class="text-subtitle-2 mt-3 mb-1">{{ t('rs.pool.colSkills') }}</div>
          <VRow v-for="(s, i) in edit.skills" :key="i" dense align="center">
            <VCol cols="6"><VTextField v-model="s.skill" :label="t('rs.pool.skillName')" hide-details /></VCol>
            <VCol cols="5"><VRating v-model="s.level" length="5" density="compact" size="small" color="primary" :aria-label="t('rs.pool.level')" /></VCol>
            <VCol cols="1"><VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="edit.skills.splice(i, 1)"><VIcon icon="ri-close-line" /></VBtn></VCol>
          </VRow>
          <VBtn variant="tonal" size="small" prepend-icon="ri-add-line" class="mt-2" @click="addSkill">{{ t('rs.pool.addSkill') }}</VBtn>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="edit.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="edit.saving" @click="saveEdit">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.matrix-wrap {
  overflow-x: auto;
}

.matrix {
  border-collapse: collapse;
  font-size: 12.5px;
  min-width: 100%;
}

.matrix th,
.matrix td {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 6px 8px;
  text-align: center;
  white-space: nowrap;
}

.matrix th {
  color: rgb(var(--v-theme-muted));
  font-weight: 600;
}

.matrix .sticky {
  position: sticky;
  left: 0;
  background: rgb(var(--v-theme-surface));
  text-align: left;
  z-index: 1;
}

.matrix tr.clickable {
  cursor: pointer;
}

.lv {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.lv-1 { background: rgba(var(--v-theme-primary), 0.08); }
.lv-2 { background: rgba(var(--v-theme-primary), 0.18); }
.lv-3 { background: rgba(var(--v-theme-primary), 0.3); }
.lv-4 { background: rgba(var(--v-theme-primary), 0.45); }
.lv-5 { background: rgba(var(--v-theme-primary), 0.62); color: rgb(var(--v-theme-on-primary)); }
</style>
