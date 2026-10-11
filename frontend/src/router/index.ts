import { createRouter } from 'vue-router'
import type { NavigationGuardWithThis, Router, RouterHistory, RouteRecordRaw } from 'vue-router'
import { fetchCurrentUser } from '../api/auth'
import { ApiClientError } from '../api/client'
import { sessionState, setCurrentUser } from '../stores/session'

export const routes: RouteRecordRaw[] = [
  { path: '/system', name: 'system', component: () => import('../views/SystemView.vue') },
  { path: '/', redirect: '/offerings' },
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/offerings', name: 'offerings', component: () => import('../views/OfferingsView.vue'), meta: { requiresAuth: true } },
  { path: '/imports', name: 'imports', component: () => import('../views/ImportView.vue'), meta: { requiresAuth: true } },
  { path: '/offerings/detail/:offeringId?', name: 'offering-detail', component: () => import('../views/OfferingDetailView.vue'), meta: { requiresAuth: true } },
  { path: '/classrooms/:classSessionId', name: 'classroom-analysis', component: () => import('../views/ClassroomAnalysisView.vue'), meta: { requiresAuth: true } },
  // S2-02 证据回看：绑定一节课（可选 ?mediaId= 指定哪段媒体），播放与证据联动都在这一页。
  { path: '/classrooms/:classSessionId/evidence', name: 'evidence-review', component: () => import('../views/EvidenceReviewView.vue'), meta: { requiresAuth: true } },
  { path: '/comparison', name: 'comparison', component: () => import('../views/ComparisonView.vue'), meta: { requiresAuth: true } },
  { path: '/my-schedule', name: 'my-schedule', component: () => import('../views/MyScheduleView.vue'), meta: { requiresAuth: true } },
  { path: '/my-actions', name: 'my-actions', component: () => import('../views/MyActionsView.vue'), meta: { requiresAuth: true } },
  { path: '/my-courses', redirect: '/my-schedule' },
  { path: '/supervision/coverage', name: 'coverage', component: () => import('../views/CoverageView.vue'), meta: { requiresAuth: true } },
  // 复核必须绑定一节课：带 mediaId 的是复核页本身，不带 mediaId 的入口页只做指路提示。
  // 静态路径写在参数路径之前，避免 /reports/review 被当成 mediaId。
  { path: '/reports/review', name: 'report-review-index', component: () => import('../views/SupervisorReportReviewView.vue'), meta: { requiresAuth: true } },
  { path: '/reports/:mediaId/review', name: 'report-review', component: () => import('../views/SupervisorReportReviewView.vue'), meta: { requiresAuth: true } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue') },
]

/**
 * Navigation guard for UX only: it restores the current user once and can redirect to
 * the login page, but the real authorization boundary is always the server response.
 */
function createAuthGuard(): NavigationGuardWithThis<undefined> {
  return async to => {
    if (to.meta.requiresAuth !== true) return true
    if (sessionState.currentUser !== null) return true
    try {
      setCurrentUser(await fetchCurrentUser())
      return true
    } catch (cause) {
      if (cause instanceof ApiClientError && cause.status === 401) {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
      return true
    }
  }
}

export function createAppRouter(history: RouterHistory): Router {
  const router = createRouter({ history, routes })
  router.beforeEach(createAuthGuard())
  return router
}
