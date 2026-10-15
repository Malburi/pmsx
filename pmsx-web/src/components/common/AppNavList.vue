<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NAV_ITEMS, filterByProjectType, filterByRoles, isEnabled, type NavItem } from '@/navigation/menu'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'

defineProps<{ rail: boolean }>()

const { t } = useI18n()
const auth = useAuthStore()
const projectStore = useProjectStore()

// 메뉴 그룹 기본 펼침 — As-Is 분석·운영(SM)·관리를 연다
const opened = ref<string[]>(['asis', 'sm', 'admin'])

// ─────────── 헬퍼 ───────────
const releaseHint = (item: NavItem) => t('layout.nav.comingSoon', { release: item.release })

// ─────────── computed ───────────
// 역할 → 프로젝트 유형(SI/SM) 순으로 거른다
const items = computed(() =>
  filterByProjectType(filterByRoles(NAV_ITEMS, (roles) => auth.hasAnyRole(roles)), projectStore.currentProject?.type ?? null))
</script>

<template>
  <VList v-model:opened="opened" density="compact" nav aria-label="main">
    <template v-for="group in items" :key="group.key">
      <VListGroup v-if="group.children" :value="group.key">
        <template #activator="{ props: groupProps }">
          <VListItem
            v-bind="groupProps"
            :prepend-icon="group.icon"
            :title="t(group.titleKey)"
            :class="{ 'nav-disabled': !isEnabled(group) }"
          />
        </template>
        <VListItem
          v-for="item in group.children"
          :key="item.key"
          :to="item.to"
          :disabled="!item.to"
          :title="t(item.titleKey)"
          :value="item.key"
          :data-test="`nav-${item.key}`"
        >
          <template v-if="!item.to" #append>
            <VChip size="x-small" variant="tonal" :title="releaseHint(item)">{{ item.release }}</VChip>
          </template>
        </VListItem>
      </VListGroup>
      <VListItem
        v-else
        :to="group.to"
        :disabled="!group.to"
        :prepend-icon="group.icon"
        :title="t(group.titleKey)"
      >
        <template v-if="!group.to && !rail" #append>
          <VChip size="x-small" variant="tonal">{{ group.release }}</VChip>
        </template>
      </VListItem>
    </template>
  </VList>
</template>

<style scoped>
.nav-disabled {
  opacity: 0.6;
}
</style>
