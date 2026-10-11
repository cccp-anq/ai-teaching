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
  { path: '/comparison', name: 'comparison', component: () => import('../views/ComparisonView.vue'), meta: { requiresAuth: true } },
  { path: '/my-schedule', name: 'my-schedule', component: () => import('../views/MyScheduleView.vue'), meta: { requiresAuth: true } },
  { path: '/my-courses', redirect: '/my-schedule' },
  { path: '/supervision/coverage', name: 'coverage', component: () => import('../views/CoverageView.vue'), meta: { requiresAuth: true } },
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
