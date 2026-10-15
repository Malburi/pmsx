<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getMenuTreeApi } from '@/api/asisApi'
import { getMemberScopeApi, saveMemberScopeApi } from '@/api/dashboardApi'
import { getSystemsApi } from '@/api/projectApi'
import { getChecklistApi, saveChecklistApi } from '@/api/resourceApi'
import type { MenuNode, SystemSummary } from '@/types/api'
import type { Member } from '@/types/common'
import type { ChecklistItem } from '@/types/r2'
import { formatDateTime } from '@/utils/format'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// 멤버 부가 설정: 투입 체크리스트(FR-RS-009) · PARTNER 열람 범위(docs/12 §7)
const props = defineProps<{ modelValue: boolean; projectId: string | null; member: Member | null; mode: 'checklist' | 'scope'; readonly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()

const { t } = useI18n()
const systems = ref<SystemSummary[]>([])
const items = ref<ChecklistItem[]>([])
const newItem = ref('')
const scope = reactive({ systemIds: [] as string[], menuIds: [] as string[] })
const menus = ref<{ title: string; value: string }[]>([])
const saving = ref(false)

// ─────────── 헬퍼 ───────────
const flatMenus = (nodes: MenuNode[], prefix: string, out: { title: string; value: string }[]) => {
  nodes.forEach((n) => {
    out.push({ title: `${prefix}${n.name}`, value: n.menuId })
    flatMenus(n.children, `${prefix}${n.name} › `, out)
  })
  return out
}

const load = async () => {
  if (!props.projectId || !props.member) return
  if (props.mode === 'checklist') {
    items.value = await getChecklistApi(props.projectId, props.member.userId).catch(() => [])
  } else {
    Object.assign(scope, { systemIds: [], menuIds: [] })
    // 저장된 범위를 초기값으로 (없으면 빈 범위)
    const saved = await getMemberScopeApi(props.projectId, props.member.userId).catch(() => null)
    if (saved) Object.assign(scope, { systemIds: [...saved.systemIds], menuIds: [...saved.menuIds] })
    // 관리 화면에서 고른 프로젝트는 현재 프로젝트와 다를 수 있어 그 프로젝트의 시스템을 따로 읽는다
    systems.value = await getSystemsApi(props.projectId).catch(() => [])
    const trees = await Promise.all(systems.value.map(async (s) => ({ s, tree: await getMenuTreeApi(s.systemId).catch(() => [] as MenuNode[]) })))
    menus.value = trees.flatMap(({ s, tree }) => flatMenus(tree, `${s.name} › `, []))
  }
}

const save = async () => {
  if (!props.projectId || !props.member) return
  saving.value = true
  try {
    if (props.mode === 'checklist') items.value = await saveChecklistApi(props.projectId, props.member.userId, items.value)
    else await saveMemberScopeApi(props.projectId, props.member.userId, { ...scope })
    showSuccessToast('common.saved')
    emit('update:modelValue', false)
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const addItem = () => {
  if (!newItem.value.trim()) return
  items.value.push({ item: newItem.value.trim(), done: false, doneAt: null })
  newItem.value = ''
}

// ─────────── computed ───────────
const systemItems = computed(() => systems.value.map((s) => ({ title: `${s.name} (${s.code})`, value: s.systemId })))
const doneCount = computed(() => items.value.filter((i) => i.done).length)

watch(() => props.modelValue, (open) => { if (open) void load() })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="520" @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="mode === 'checklist' ? t('admin.projects.checklist', { name: member?.name ?? '' }) : t('admin.projects.scope', { name: member?.name ?? '' })">
      <VCardText v-if="mode === 'checklist'">
        <div class="text-caption text-muted mb-2">{{ t('admin.projects.checklistProgress', { done: doneCount, total: items.length }) }}</div>
        <VList density="compact" class="py-0">
          <VListItem v-for="(it, i) in items" :key="i" class="px-0">
            <template #prepend><VCheckboxBtn v-model="it.done" :disabled="readonly" :aria-label="it.item" /></template>
            <VListItemTitle>{{ it.item }}</VListItemTitle>
            <VListItemSubtitle v-if="it.doneAt">{{ formatDateTime(it.doneAt) }}</VListItemSubtitle>
          </VListItem>
        </VList>
        <div v-if="!readonly" class="d-flex ga-2 mt-2">
          <VTextField v-model="newItem" :label="t('admin.projects.checklistAdd')" hide-details @keyup.enter="addItem" />
          <VBtn variant="tonal" @click="addItem">{{ t('common.add') }}</VBtn>
        </div>
      </VCardText>
      <VCardText v-else>
        <VAlert type="info" variant="tonal" density="compact" class="mb-3">{{ t('admin.projects.scopeHint') }}</VAlert>
        <VAutocomplete v-model="scope.systemIds" :items="systemItems" :label="t('admin.projects.scopeSystems')" multiple chips closable-chips class="mb-3" />
        <VAutocomplete v-model="scope.menuIds" :items="menus" :label="t('admin.projects.scopeMenus')" multiple chips closable-chips :hint="t('admin.projects.scopeMenusHint')" persistent-hint />
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn v-if="!readonly" color="primary" :loading="saving" @click="save">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
