<template>
  <div class="page">
    <h2>교육과정 관리</h2>
    <form class="search" @submit.prevent="search">
      <label for="searchYear">연도</label>
      <select id="searchYear" v-model="cond.year" @change="search">
        <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
      </select>
      <label for="searchDept">주관부서</label>
      <select id="searchDept" v-model="cond.deptCd">
        <option value="">전체</option>
        <option v-for="d in depts" :key="d.deptCd" :value="d.deptCd">{{ d.deptNm }}</option>
      </select>
      <label for="searchKeyword">과정명</label>
      <input id="searchKeyword" v-model="cond.keyword" @keyup.enter="search" />
      <button id="btnSearch" type="button" @click="search">조회</button>
    </form>
    <div class="btns">
      <button id="btnNew" type="button" @click="openNew">신규</button>
      <button id="btnExcel" type="button" @click="downloadExcel()">엑셀</button>
      <button id="btnGoApply" type="button" @click="router.push({ name: 'EduApply' })">수강신청 화면</button>
    </div>
    <table id="grdCourse">
      <thead>
        <tr><th>과정코드</th><th>과정명</th><th>교육기간</th><th>정원</th><th>관리</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.courseId" @dblclick="openEdit(row)">
          <td>{{ row.courseId }}</td><td>{{ row.courseNm }}</td><td>{{ row.startDt }} ~ {{ row.endDt }}</td><td>{{ row.capacity }}</td>
          <td><button type="button" class="btn-del" @click="remove(row)">삭제</button></td>
        </tr>
      </tbody>
    </table>
    <EduCourseDialog v-model="dialogVisible" :course="selected" @saved="search" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCoursesApi, deleteCourseApi, downloadCoursesExcelApi } from '@/api/eduApi'
import codeApi from '@/api/codeApi'
import EduCourseDialog from '@/components/EduCourseDialog.vue'

const router = useRouter()
const years = [2026, 2025, 2024]
const cond = reactive({ year: 2026, deptCd: '', keyword: '' })
const rows = ref([])
const depts = ref([])
const dialogVisible = ref(false)
const selected = ref(null)

async function search() {
  rows.value = await getCoursesApi({ ...cond })
}

const openNew = () => {
  selected.value = null
  dialogVisible.value = true
}

function openEdit(row) {
  selected.value = row
  dialogVisible.value = true
}

async function remove(row) {
  if (!confirm('삭제하시겠습니까?')) return
  await deleteCourseApi(row.courseId)
  await search()
}

async function downloadExcel() {
  await downloadCoursesExcelApi(cond.year)
}

onMounted(async () => {
  depts.value = await codeApi.getDeptList()
  await search()
})
</script>
