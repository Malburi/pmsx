<template>
  <div class="page">
    <h2>교육 실적 통계</h2>
    <div class="search">
      <label for="statYear">연도</label>
      <select id="statYear" v-model="year" @change="load">
        <option :value="2026">2026</option><option :value="2025">2025</option>
      </select>
      <button id="btnStatSearch" type="button" @click="load">조회</button>
    </div>
    <table id="grdStat">
      <thead><tr><th>부서</th><th>과정수</th><th>수료인원</th><th>수료율</th></tr></thead>
      <tbody><tr v-for="s in stats" :key="s.deptNm"><td>{{ s.deptNm }}</td><td>{{ s.courseCnt }}</td><td>{{ s.doneCnt }}</td><td>{{ s.rate }}%</td></tr></tbody>
    </table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const year = ref(2026)
const stats = ref([])

async function load() {
  const res = await fetch('/api/stat/edu?year=' + year.value)
  stats.value = await res.json()
}

onMounted(load)
</script>
