import { expect, test, type Page } from '@playwright/test'

/**
 * Sprint 1 real-browser acceptance. Real browser, real backend, real session cookies.
 * Every account is the anonymous demo bootstrap world (Round 3.8 BOOT, DEC-024);
 * the demo password is provided by the environment, never committed.
 */

const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD
  ?? (() => { throw new Error('E2E_DEMO_PASSWORD is required') })()

async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/offerings$/)
}

async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
}

test('anonymous visit redirects to the login page', async ({ page }) => {
  await page.goto('/offerings')
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
})

test('admin walks login → query → detail → five-teacher comparison', async ({ page }) => {
  await login(page, 'demo-admin', DEMO_PASSWORD)

  // ---- Query: ownerCollege vs sourceScope, AC-03 cross combos -------------------------
  await page.goto('/offerings')
  await expect(page.getByLabel('课程归属学院')).toBeVisible()
  await expect(page.getByLabel('来源学院（数据范围）')).toBeVisible()
  // Owner college is a Course-level filter: 演示软件学院 owns the mapped demo offerings.
  await page.getByLabel('课程归属学院').selectOption({ label: '演示软件学院（DEMO-SOFT）' })
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByText('演示开课数据结构甲班')).toBeVisible()
  await expect(page.getByText('演示开课时间地点匹配')).toBeVisible()
  await expect(page.getByText('演示开课算法设计')).toBeHidden()
  // Clearing restores the authorized default list.
  await page.getByRole('button', { name: '清空筛选' }).click()
  await expect(page.getByText('演示开课算法设计')).toBeVisible()

  // AC-03 vertical: week1 + room C hits; the cross combinations stay empty.
  const runQuery = async (week: string, room: string, expected: 'hit' | 'empty') => {
    await page.getByLabel('课程名称').fill('演示开课时间地点匹配')
    await page.getByLabel('周次').selectOption({ label: week })
    await page.getByLabel('上课地点').fill(room)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    if (expected === 'hit') {
      await expect(page.getByText('演示开课时间地点匹配')).toBeVisible()
    } else {
      await expect(page.getByText('未找到符合当前筛选条件的开课')).toBeVisible()
    }
  }
  await runQuery('第1周', '演示教室C', 'hit')
  await runQuery('第2周', '演示教室D', 'hit')
  await runQuery('第1周', '演示教室D', 'empty')
  await runQuery('第2周', '演示教室C', 'empty')

  // ---- Detail: description, NULL course, owner/source difference, p=0, ONLINE --------
  // Reload to drop the AC-03 conditions; clearing via the form would work as well.
  await page.goto('/offerings')
  await page.getByLabel('课程名称').fill('演示开课数据结构甲班')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page.getByText('演示课程简介：面向本科生的数据结构入门课程')).toBeVisible()
  await expect(page.getByText('45')).toBeVisible()
  // Co-teaching on the same schedule row.
  await expect(page.getByText('演示教师甲').first()).toBeVisible()
  await expect(page.getByText('演示教师乙').first()).toBeVisible()

  await page.goto('/offerings')
  await page.getByLabel('课程名称').fill('演示开课算法设计')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page.getByText('暂无课程简介')).toBeVisible()
  await expect(page.getByText('0').first()).toBeVisible() // studentCount stays the number 0
  await expect(page.getByText('线上').first()).toBeVisible()

  await page.goto('/offerings')
  await page.getByLabel('课程名称').fill('演示实践环节未映射课程')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  // Unmapped offering: course code and owner college render the missing marker.
  await expect(page.getByText('—').first()).toBeVisible()

  // ---- Comparison: authorized dropdown, 5 teachers, co-teaching -----------------------
  await page.goto('/comparison')
  const dropdownAdd = page.locator('.manual-add').first().getByRole('button', { name: '添加' })
  const demoTeachers = ['演示教师甲', '演示教师乙', '演示教师丙', '演示教师丁', '演示教师戊']
  for (const [index, name] of demoTeachers.entries()) {
    await expect(page.getByLabel('从授权教师列表选择')).toContainText(name)
    await page.getByLabel('从授权教师列表选择').selectOption(String(index + 1))
    await dropdownAdd.click()
  }
  await expect(page.getByText('已选教师（5/5）')).toBeVisible()
  await page.getByRole('button', { name: '开始比较' }).click()
  await expect(page.getByText('演示开课数据结构甲班').first()).toBeVisible()
  // Co-teaching is kept in both groups of the shared schedule.
  await expect(page.getByText('演示教师乙 + 演示教师甲').first()).toBeVisible()
  // Unmapped offering shows the description-missing copy inside the comparison.
  await expect(page.getByText('暂无课程简介').first()).toBeVisible()

  await logout(page)
})

test('teacher cannot open an offering outside the authorized dataset', async ({ page, request }) => {
  // Admin API session finds the unmapped offering id (the teacher account cannot see it).
  const csrf = await request.get('/api/v1/auth/csrf')
  const { token, headerName } = await csrf.json()
  const loginResponse = await request.post('/api/v1/auth/login', {
    form: { username: 'demo-admin', password: DEMO_PASSWORD },
    headers: { [headerName]: token },
  })
  expect(loginResponse.ok()).toBeTruthy()
  const offerings = await (await request.get('/api/v1/course-offerings?courseName=演示实践环节未映射课程')).json()
  const hiddenId = offerings.items[0].offeringId as number

  await login(page, 'demo-teacher', DEMO_PASSWORD)
  await page.goto('/my-schedule')
  await expect(page.getByRole('heading', { name: '我的课表' })).toBeVisible()
  await expect(page.locator('.purpose-tag.teaching').first()).toHaveText('授课')
  await expect(page.locator('.purpose-tag.supervision')).toHaveCount(0)
  await page.goto(`/offerings/detail/${hiddenId}`)
  await expect(page.getByText('该开课不存在或当前账号不可见')).toBeVisible()
})

test('comparison keeps fail-closed texts without leaking an unauthorized teacher identity', async ({ page, request }) => {
  await login(page, 'demo-supervisor', DEMO_PASSWORD)
  await page.goto('/my-schedule')
  await expect(page.locator('.purpose-tag.supervision').first()).toHaveText('督导')
  await expect(page.locator('.purpose-tag.teaching')).toHaveCount(0)
  await page.goto('/comparison')
  // The supervisor's dataset only contains 演示教师丙; the dropdown never leaks others.
  const options = page.getByLabel('从授权教师列表选择').locator('option')
  await expect(options).toHaveCount(2) // placeholder + 演示教师丙
  // Unknown id through the manual fallback → structured 422 text.
  await page.getByLabel('教师编号（回退方式）').fill('999999')
  await page.locator('.manual-add').nth(1).getByRole('button', { name: '添加' }).click()
  await page.getByRole('button', { name: '开始比较' }).click()
  await expect(page.getByText('所选教师不存在或不可用')).toBeVisible()
  // Add a real but unauthorized teacher id via the manual fallback, then drop the unknown
  // pick. Both cases deliberately collapse to the same response and reveal no identity.
  await page.getByLabel('教师编号（回退方式）').fill('1')
  await page.locator('.manual-add').nth(1).getByRole('button', { name: '添加' }).click()
  await page.locator('.pick-list li', { hasText: '999999' }).getByRole('button', { name: '移除' }).click()
  await expect(page.getByText('已选教师（1/5）')).toBeVisible()
  await page.getByRole('button', { name: '开始比较' }).click()
  await expect(page.getByText('所选教师不存在或不可用')).toBeVisible()
  await expect(page.getByText('演示教师甲')).toHaveCount(0)
  await logout(page)
})
