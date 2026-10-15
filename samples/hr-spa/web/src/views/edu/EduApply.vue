<template>
  <div class="page">
    <h2>수강 신청</h2>
    <div class="search">
      <label for="applyStatus">신청상태</label>
      <select id="applyStatus" v-model="status" @change="loadApplies">
        <option value="">전체</option><option value="REQ">신청</option><option value="OK">승인</option>
      </select>
      <button id="btnApplySearch" type="button" @click="loadApplies">조회</button>
      <button id="btnMyApplies" type="button" @click="loadMine">내 신청내역</button>
    </div>
    <table id="grdApply">
      <thead><tr><th>신청번호</th><th>과정명</th><th>신청자</th><th>상태</th><th>처리</th></tr></thead>
      <tbody>
        <tr v-for="a in applies" :key="a.applyId">
          <td>{{ a.applyId }}</td><td>{{ a.courseNm }}</td><td>{{ a.empNm }}</td><td>{{ a.status }}</td>
          <td><button type="button" class="btn-cancel" @click="cancel(a)">신청취소</button></td>
        </tr>
      </tbody>
    </table>
    <div class="btns">
      <button id="btnApply" type="button" @click="apply">수강신청</button>
      <button id="btnBack" type="button" @click="goList">과정 목록</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { getAppliesApi, applyCourseApi, cancelApplyApi } from '@/api/eduApi'

const router = useRouter()
const status = ref('')
const applies = ref([])

const loadApplies = async () => {
  applies.value = await getAppliesApi({ status: status.value })
}

async function loadMine() {
  const res = await axios.get('/api/edu/my-applies')
  applies.value = res.data
}

async function apply() {
  await applyCourseApi({ courseId: 'C2026-001' })
  await loadApplies()
}

async function cancel(a) {
  if (!window.confirm('신청을 취소하시겠습니까?')) return
  await cancelApplyApi(a.applyId)
  await loadApplies()
}

function goList() {
  router.push('/edu/courses')
}

onMounted(loadApplies)
</script>
