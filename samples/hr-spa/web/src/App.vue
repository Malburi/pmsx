<template>
  <div class="layout">
    <nav class="nav">
      <strong>교육관리시스템</strong>
      <h3>교육관리</h3>
      <router-link id="lnkCourses" to="/edu/courses">교육과정 관리</router-link>
      <router-link id="lnkApply" to="/edu/apply">수강 신청</router-link>
      <h3>통계</h3>
      <router-link id="lnkStat" to="/stat/edu">교육 실적 통계</router-link>
    </nav>
    <main><router-view /></main>
  </div>
</template>
