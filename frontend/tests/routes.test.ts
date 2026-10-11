import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createMemoryHistory } from 'vue-router'
import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router'
import { ApiClientError, createHttpClient } from '../src/api/client'
import { markUnauthenticated, setCurrentUser } from '../src/stores/session'
import type { CurrentUser } from '../src/api/types'

const testUser: CurrentUser = {
  userId: 3, username: 'teacher-a-test', teacherId: 1, roles: ['TEACHER'],
  authorizationScopes: [{ roleCode: 'TEACHER', scopeType: 'SELF_TEACHER', collegeId: null, teacherId: 1, offeringId: null }],
}

vi.mock('../src/api/auth', () => ({
  fetchCurrentUser: vi.fn(async () => testUser),
  fetchLoginMode: vi.fn(async () => ({ usernameOnly: false })),
  fetchCsrf: vi.fn(async () => ({ token: 't', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' })),
  login: vi.fn(async () => testUser),
  logout: vi.fn(async () => undefined),
}))

const fetchCurrentUserMock = (await import('../src/api/auth')).fetchCurrentUser as Mock

async function open(path: string) {
  const router = createAppRouter(createMemoryHistory())
  const app = createSSRApp(App).use(router)
  await router.push(path)
  await router.isReady()
  return { router, app }
}

describe('sprint 1 routes', () => {
  beforeEach(() => {
    setCurrentUser(null)
    fetchCurrentUserMock.mockClear()
    fetchCurrentUserMock.mockImplementation(async () => testUser)
  })

  it('renders the login page with a real form', async () => {
    const html = await renderToString((await open('/login')).app)
    expect(html).toContain('<h1>登录</h1>')
    expect(html).toContain('id="login-username"')
    expect(html).toContain('id="login-username"')
    expect(html).toContain('type="submit"')
  })

  it('restores the session through the guard and renders the offering query page', async () => {
    const { app } = await open('/offerings')
    expect(fetchCurrentUserMock).toHaveBeenCalledTimes(1)
    const html = await renderToString(app)
    expect(html).toContain('<h1>开课查询</h1>')
    expect(html).toContain('来源学院（数据范围）')
    expect(html).toContain('清空筛选')
  })

  it('does not call the server again when the current user is already known', async () => {
    setCurrentUser(testUser)
    await open('/offerings')
    expect(fetchCurrentUserMock).not.toHaveBeenCalled()
  })

  it('redirects an anonymous visitor from a protected page to the login page', async () => {
    fetchCurrentUserMock.mockImplementation(async () => {
      throw new ApiClientError('尚未登录或登录状态已失效。', 'UNAUTHENTICATED', 401)
    })
    const { router } = await open('/offerings')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/offerings')
  })

  it('keeps unauthenticated users on public pages without server calls', async () => {
    await open('/system')
    expect(fetchCurrentUserMock).not.toHaveBeenCalled()
  })

  it('renders the detail page with an invalid id hint instead of a request', async () => {
    setCurrentUser(testUser)
    const html = await renderToString((await open('/offerings/detail/not-a-number')).app)
    expect(html).toContain('开课编号不合法')
  })

  it('registers the protected classroom analysis page', async () => {
    setCurrentUser(testUser)
    const { router, app } = await open('/classrooms/91')
    expect(router.currentRoute.value.name).toBe('classroom-analysis')
    expect(await renderToString(app)).toContain('课堂分析')
  })

  it('renders the comparison page with selection limits and no teacher directory', async () => {
    setCurrentUser(testUser)
    const html = await renderToString((await open('/comparison')).app)
    expect(html).toContain('多教师开课比较')
    expect(html).toContain('尚未选择教师')
    expect(html).toContain('教师编号')
  })

  it('renders the purpose-separated personal schedule behind the guard', async () => {
    setCurrentUser(testUser)
    const html = await renderToString((await open('/my-schedule')).app)
    expect(html).toContain('<h1>我的课表</h1>')
    expect(html).toContain('授课安排和督导安排')
    expect(html).toContain('正在加载我的课表…')
    expect((await open('/my-courses')).router.currentRoute.value.path).toBe('/my-schedule')
  })

  it('renders the supervision text entry without timetable or preparation forms', async () => {
    setCurrentUser({ ...testUser, roles: ['ADMIN'] })
    const html = await renderToString((await open('/imports')).app)
    expect(html).toContain('<h1>督导评价录入</h1>')
    expect(html).toContain('上传 TXT 文件')
    expect(html).not.toContain('课表数据导入')
    expect(html).not.toContain('备课数据导入')
  })

  it('renders the coverage page with filters and provisional scope note', async () => {
    setCurrentUser(testUser)
    const html = await renderToString((await open('/supervision/coverage')).app)
    expect(html).toContain('<h1>督导覆盖率</h1>')
    expect(html).toContain('课程归属学院')
    expect(html).toContain('暂定口径')
    expect(html).toContain('正在计算覆盖率…')
  })

  it('redirects root to offerings and displays unknown routes as 404', async () => {
    expect((await open('/')).router.currentRoute.value.name).toBe('offerings')
    expect(await renderToString((await open('/does-not-exist')).app)).toContain('<h1>页面不存在</h1>')
  })

  it('shows a login notice after 401 while preserving the current route without redirect loops', async () => {
    setCurrentUser(testUser)
    const { app, router } = await open('/comparison')
    const client = createHttpClient({ onUnauthenticated: markUnauthenticated, adapter: async config => {
      throw new AxiosError('unauthenticated', 'ERR_BAD_REQUEST', config, {}, {
        status: 401, statusText: 'Unauthorized', data: {}, headers: {}, config,
      })
    } })
    await expect(client.get('/test')).rejects.toMatchObject({ status: 401 })
    const html = await renderToString(app)
    expect(html).toContain('role="alert"')
    expect(html).toContain('尚未登录或登录状态已失效')
    expect(router.currentRoute.value.path).toBe('/comparison')
  })
})
