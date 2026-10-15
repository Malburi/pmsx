<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PMO, PM]
</route>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getRolesApi } from '@/api/adminApi'
import { lookupUsersApi } from '@/api/userApi'
import { addMemberApi, getMembersApi, removeMemberApi, updateMemberApi } from '@/api/projectApi'
import HandoverWizard from '@/components/admin/HandoverWizard.vue'
import MemberExtrasDialog from '@/components/admin/MemberExtrasDialog.vue'
import ProjectFormDialog from '@/components/admin/ProjectFormDialog.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useCodes } from '@/composables/useCodes'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type { ApiError, Project } from '@/types/api'
import type { Member, Role, UserLookup } from '@/types/common'
import { HANDOVER_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

const { t } = useI18n()
const auth = useAuthStore()
const projectStore = useProjectStore()
const { projects, currentProjectId } = storeToRefs(projectStore)
const { label: codeLabel } = useCodes(['ORG_TYPE', 'PROJECT_TYPE'])

const selectedId = ref<string | null>(currentProjectId.value)
const members = ref<Member[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const roles = ref<Role[]>([])

const dialog = ref(false)
const editing = ref<Member | null>(null)
const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const form = reactive({ userId: '' as string | null, roleId: '', inDate: '', outDate: '' })
const saving = ref(false)
const formError = ref<string | null>(null)
const userOptions = ref<{ title: string; value: string }[]>([])
const userSearching = ref(false)
// 사용자 목록 API 는 SYS_ADMIN 전용 — 권한이 없으면 사용자 ID 를 직접 입력받는다
const userLookupDenied = ref(false)
const removeTarget = ref<Member | null>(null)
const removing = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null
// R2: 프로젝트 등록·수정, 멤버 투입 체크리스트·PARTNER 열람 범위
const projectDialog = reactive({ open: false, project: null as Project | null })
const extras = reactive({ open: false, member: null as Member | null, mode: 'checklist' as 'checklist' | 'scope' })
// R3: SI → SM 인수인계 마법사 (FR-SM-013)
const handoverOpen = ref(false)

// ─────────── 헬퍼 ───────────
const required = (v: unknown) => !!String(v ?? '').trim() || t('validation.required')
const dateOrder = () => !form.inDate || !form.outDate || form.inDate <= form.outDate || t('validation.dateOrder')

const load = async (projectId: string) => {
  loading.value = true
  loadError.value = null
  try {
    members.value = await getMembersApi(projectId)
  } catch (e) {
    members.value = []
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const loadRoles = async () => {
  try {
    roles.value = await getRolesApi()
  } catch {
    roles.value = []
  }
}

// 사용자 검색은 입력이 멈춘 뒤 300ms 후 호출 (forms-validation §1)
const searchUsers = (text: string | null) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (userLookupDenied.value || editing.value) return
  searchTimer = setTimeout(async () => {
    userSearching.value = true
    try {
      const res = await lookupUsersApi(text ?? '')
      userOptions.value = res.map((u: UserLookup) => ({ title: `${u.name} (${u.loginId})`, value: u.userId }))
    } catch (e) {
      if ((e as ApiError).code === 'FORBIDDEN') userLookupDenied.value = true
    } finally {
      userSearching.value = false
    }
  }, 300)
}

const openAdd = () => {
  editing.value = null
  Object.assign(form, { userId: null, roleId: projectRoleItems.value[0]?.value ?? '', inDate: '', outDate: '' })
  formError.value = null
  dialog.value = true
  searchUsers('')
}

const openEdit = (m: Member) => {
  editing.value = m
  Object.assign(form, { userId: m.userId, roleId: m.roleId, inDate: m.inDate ?? '', outDate: m.outDate ?? '' })
  formError.value = null
  dialog.value = true
}

const save = async () => {
  const result = await formRef.value?.validate()
  if (!result?.valid || !selectedId.value || !form.userId) return
  saving.value = true
  formError.value = null
  const payload = { userId: form.userId, roleId: form.roleId, inDate: form.inDate || null, outDate: form.outDate || null }
  try {
    if (editing.value) await updateMemberApi(selectedId.value, editing.value.userId, payload)
    else await addMemberApi(selectedId.value, payload)
    dialog.value = false
    showSuccessToast(editing.value ? 'admin.projects.memberUpdated' : 'admin.projects.memberAdded')
    await load(selectedId.value)
  } catch (e) {
    const err = e as ApiError
    formError.value = err.code === 'CONFLICT' ? t('admin.projects.memberConflict')
      : err.code === 'NOT_FOUND' ? t('admin.projects.userNotFound')
      : (err.message ?? t('common.error.unknown'))
  } finally {
    saving.value = false
  }
}

const remove = async () => {
  if (!removeTarget.value || !selectedId.value) return
  removing.value = true
  try {
    await removeMemberApi(selectedId.value, removeTarget.value.userId)
    removeTarget.value = null
    showSuccessToast('admin.projects.memberRemoved')
    await load(selectedId.value)
  } catch {
    showErrorToast('common.error.unknown')
  } finally {
    removing.value = false
  }
}

const onProjectSaved = async (p: Project) => {
  showSuccessToast('common.saved')
  await projectStore.loadProjects()
  selectedId.value = p.projectId
}

const openExtras = (m: Member, mode: 'checklist' | 'scope') => Object.assign(extras, { open: true, member: m, mode })

const roleName = (roleId: string) => roles.value.find((r) => r.roleId === roleId)?.name ?? roleId

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(['SYS_ADMIN', 'PM']))
const canCreate = computed(() => auth.hasAnyRole(['SYS_ADMIN', 'PMO']))
const canHandover = computed(() => auth.hasAnyRole(HANDOVER_ROLES) && selectedProject.value?.type === 'SI')
const selectedProject = computed(() => projects.value.find((p) => p.projectId === selectedId.value) ?? null)
const projectRoleItems = computed(() =>
  roles.value.filter((r) => r.scope === 'PROJECT').map((r) => ({ title: `${r.name} (${r.roleId})`, value: r.roleId })))

const headers = computed(() => [
  { title: t('admin.users.name'), key: 'name' },
  { title: t('admin.users.loginId'), key: 'loginId', width: 140 },
  { title: t('admin.users.orgType'), key: 'orgType', width: 100 },
  { title: t('admin.projects.role'), key: 'roleId', width: 160 },
  { title: t('admin.projects.period'), key: 'period', sortable: false, width: 220 },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 170 },
])

// ─────────── watch ───────────
watch(projects, (list) => {
  if (!selectedId.value && list.length) selectedId.value = list[0].projectId
}, { immediate: true })

watch(selectedId, (id) => { if (id) void load(id) }, { immediate: true })

// ─────────── 라이프사이클 ───────────
onMounted(loadRoles)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <h1 class="pmsx-page-title">{{ t('nav.admin.project') }}</h1>
        <div class="text-body-2 text-muted">{{ t('admin.projects.subtitle') }}</div>
      </VCol>

      <VCol cols="12" md="4" lg="3">
        <VCard border flat :title="t('admin.projects.listTitle')">
          <template v-if="canCreate" #append>
            <VBtn size="small" variant="tonal" prepend-icon="ri-add-line" data-test="project-create" @click="Object.assign(projectDialog, { open: true, project: null })">{{ t('admin.projects.create') }}</VBtn>
          </template>
          <VList density="compact" nav data-test="project-list">
            <VListItem
              v-for="p in projects"
              :key="p.projectId"
              :active="p.projectId === selectedId"
              color="primary"
              @click="selectedId = p.projectId"
            >
              <VListItemTitle>{{ p.name }}</VListItemTitle>
              <VListItemSubtitle>{{ codeLabel('PROJECT_TYPE', p.type) }} · {{ p.customer }}</VListItemSubtitle>
            </VListItem>
            <VListItem v-if="!projects.length" :title="t('common.noData')" disabled />
          </VList>
        </VCard>
      </VCol>

      <VCol cols="12" md="8" lg="9">
        <VCard border flat>
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            <span>{{ t('admin.projects.membersTitle') }}</span>
            <span v-if="selectedProject" class="text-body-2 text-muted">— {{ selectedProject.name }}</span>
            <VSpacer />
            <VBtn v-if="canEdit" color="primary" size="small" prepend-icon="ri-user-add-line" :disabled="!selectedId" data-test="member-add" @click="openAdd">
              {{ t('admin.projects.addMember') }}
            </VBtn>
          </VCardTitle>
          <VCardSubtitle v-if="selectedProject" class="d-flex align-center ga-1">
            {{ selectedProject.startDate }} ~ {{ selectedProject.endDate }}
            <VBtn v-if="canEdit" variant="text" size="x-small" prepend-icon="ri-edit-line" data-test="project-edit" @click="Object.assign(projectDialog, { open: true, project: selectedProject })">{{ t('admin.projects.edit') }}</VBtn>
            <VBtn v-if="canHandover" variant="tonal" color="primary" size="x-small" prepend-icon="ri-arrow-left-right-line" data-test="project-handover" @click="handoverOpen = true">{{ t('handover.open') }}</VBtn>
            <VChip v-if="selectedProject.originProjectId" size="x-small" variant="tonal" label>{{ t('handover.fromSi') }}</VChip>
          </VCardSubtitle>
          <VAlert v-if="loadError" type="error" variant="tonal" class="ma-3">
            {{ loadError }}
            <VBtn variant="text" size="small" @click="selectedId && load(selectedId)">{{ t('common.retry') }}</VBtn>
          </VAlert>
          <VDataTable :headers="headers" :items="members" :loading="loading" item-value="userId" density="comfortable" :items-per-page="20">
            <template #item.loginId="{ item }"><span class="font-mono">{{ item.loginId }}</span></template>
            <template #item.orgType="{ item }">{{ codeLabel('ORG_TYPE', item.orgType) }}</template>
            <template #item.roleId="{ item }"><VChip size="small" variant="tonal" color="primary">{{ roleName(item.roleId) }}</VChip></template>
            <template #item.period="{ item }">{{ item.inDate ?? '-' }} ~ {{ item.outDate ?? '-' }}</template>
            <template #item.actions="{ item }">
              <div class="d-flex justify-end flex-nowrap">
                <VBtn icon="ri-list-check-2" variant="text" size="small" :aria-label="t('admin.projects.checklistBtn')" :title="t('admin.projects.checklistBtn')" @click="openExtras(item, 'checklist')" />
                <VBtn v-if="canEdit && (item.roleId === 'PARTNER' || item.orgType === 'PARTNER')" icon="ri-shield-keyhole-line" variant="text" size="small" :aria-label="t('admin.projects.scopeBtn')" :title="t('admin.projects.scopeBtn')" @click="openExtras(item, 'scope')" />
                <template v-if="canEdit">
                <VBtn icon="ri-pencil-line" variant="text" size="small" :aria-label="t('common.edit')" :title="t('common.edit')" @click="openEdit(item)" />
                <VBtn icon="ri-user-unfollow-line" variant="text" size="small" color="error" :aria-label="t('admin.projects.removeMember')" :title="t('admin.projects.removeMember')" @click="removeTarget = item" />
                </template>
              </div>
            </template>
            <template #no-data>
              <div class="py-8 text-muted">{{ t('admin.projects.noMembers') }}</div>
            </template>
          </VDataTable>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="dialog" max-width="520" persistent>
      <VCard :title="editing ? t('admin.projects.editMember') : t('admin.projects.addMember')">
        <VForm ref="formRef" @submit.prevent="save">
          <VCardText>
            <VRow>
              <VCol cols="12" md="12">
                <VTextField v-if="editing" :model-value="`${editing.name} (${editing.loginId})`" :label="t('admin.projects.user')" disabled />
                <VTextField
                  v-else-if="userLookupDenied"
                  v-model="form.userId"
                  :label="t('admin.projects.userId')"
                  :hint="t('admin.projects.userIdHint')"
                  persistent-hint
                  :rules="[required]"
                />
                <VAutocomplete
                  v-else
                  v-model="form.userId"
                  :items="userOptions"
                  :loading="userSearching"
                  :label="t('admin.projects.user')"
                  :rules="[required]"
                  no-filter
                  data-test="member-user"
                  @update:search="searchUsers"
                />
              </VCol>
              <VCol cols="12" md="12">
                <VSelect v-model="form.roleId" :items="projectRoleItems" :label="t('admin.projects.role')" :rules="[required]" data-test="member-role" />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.inDate" type="date" :label="t('admin.projects.inDate')" />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.outDate" type="date" :label="t('admin.projects.outDate')" :rules="[dateOrder]" />
              </VCol>
              <VCol v-if="formError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ formError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="dialog = false">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="saving" data-test="member-save">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>

    <ProjectFormDialog v-model="projectDialog.open" :project="projectDialog.project" @saved="onProjectSaved" />
    <HandoverWizard v-model="handoverOpen" :si-project="selectedProject" @done="(id: string) => (selectedId = id)" />
    <MemberExtrasDialog v-model="extras.open" :project-id="selectedId" :member="extras.member" :mode="extras.mode" :readonly="!canEdit" />
    <ConfirmDialog
      :model-value="!!removeTarget"
      :title="t('admin.projects.removeMember')"
      :message="t('admin.projects.removeConfirm', { name: removeTarget?.name ?? '' })"
      :confirm-text="t('admin.projects.removeMember')"
      :loading="removing"
      @update:model-value="(v: boolean) => { if (!v) removeTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>
