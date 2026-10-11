import { expect, test, type Page } from '@playwright/test'

/**
 * Sprint 3 real-browser acceptance: admin imports supervision evaluations (DAT-04),
 * a supervisor then walks the coverage loop (EVA-01/02/03) with the provisional
 * formula, multiple-evaluation dedupe and the fail-closed authorization boundary.
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

function evalTxt(rows: string[]): { name: string; mimeType: string; buffer: Buffer } {
  return {
    name: 'evaluations.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from(rows.join('\n'), 'utf-8'),
  }
}

test('admin imports supervision evaluations through the DAT-04 form', async ({ page }) => {
  await login(page, 'demo-admin', DEMO_PASSWORD)
  await page.goto('/imports')
  // Demo offering ids 1..4 come from the empty-database bootstrap (001, 002, 003, 004).
  await page.getByLabel('上传 TXT 文件').setInputFiles(evalTxt([
    '1\t已完成\t2026-08-30 10:00',
    '开课编号：1，状态：COMPLETED，完成时间：2026/08/30 11:00',
    '2 | 完成 | 2026年8月30日 12:00',
    '3 已记录',
  ]))
  await expect(page.getByLabel('评价内容')).toHaveValue(/开课编号/)
  await page.getByRole('button', { name: '提交评价' }).click()
  await expect(page.getByRole('heading', { name: '督导评价录入成功' })).toBeVisible()
  await expect(page.locator('.result-facts')).toContainText('4')

  // Duplicate import of the same file → structured ALREADY_IMPORTED.
  await page.getByRole('button', { name: '清空' }).click()
  await page.getByLabel('上传 TXT 文件').setInputFiles(evalTxt([
    '1\t已完成\t2026-08-30 10:00',
    '开课编号：1，状态：COMPLETED，完成时间：2026/08/30 11:00',
    '2 | 完成 | 2026年8月30日 12:00',
    '3 已记录',
  ]))
  await page.getByRole('button', { name: '提交评价' }).click()
  await expect(page.getByRole('heading', { name: '这些内容已经录入过' })).toBeVisible()

  // Unknown offering → the whole batch is rejected with a locatable row error.
  await page.getByRole('button', { name: '清空' }).click()
  await page.getByLabel('上传 TXT 文件').setInputFiles(evalTxt([
    '999999 完成 2026-08-30 12:00',
  ]))
  await page.getByRole('button', { name: '提交评价' }).click()
  await expect(page.getByRole('heading', { name: '督导评价录入失败' })).toBeVisible()
  await expect(page.getByText('offering 999999 does not exist')).toBeVisible()
  await logout(page)
})

test('supervisor sees coverage for the bound offering with the identity equation', async ({ page }) => {
  await login(page, 'demo-supervisor', DEMO_PASSWORD)
  await page.goto('/supervision/coverage')
  // Dataset = offering 2 only; it carries one completed evaluation.
  await expect(page.getByText('覆盖率汇总')).toBeVisible()
  await expect(page.locator('section[aria-label="覆盖率汇总"]')).toContainText('应督导开课（暂定口径）')
  const summarySection = page.locator('section[aria-label="覆盖率汇总"]')
  await expect(summarySection).toContainText('1')
  await expect(page.getByText('100.0%')).toBeVisible()
  // Covered drilldown shows offering 002; uncovered drilldown is empty.
  const coveredSection = page.locator('section[aria-label="已覆盖开课"]')
  await expect(coveredSection).toContainText('演示开课算法设计')
  await expect(page.locator('section[aria-label="未覆盖开课"]')).toContainText('没有未覆盖的开课')
  // The owner-college dropdown only offers colleges inside the supervisor's dataset.
  const ownerOptions = page.getByLabel('课程归属学院').locator('option')
  await expect(ownerOptions).toHaveCount(2) // placeholder + 演示文理学院
  await logout(page)
})

test('admin global coverage keeps the equation and the dedupe', async ({ page }) => {
  await login(page, 'demo-admin', DEMO_PASSWORD)
  await page.goto('/supervision/coverage')
  const summarySection = page.locator('section[aria-label="覆盖率汇总"]')
  // The dataset spans demo offerings plus Sprint 1's imported timetable rows: only the
  // identity equation and dedupe are asserted, never a hard-coded total.
  const eligible = Number(await summarySection.locator('dd').nth(0).textContent())
  const covered = Number(await summarySection.locator('dd').nth(1).textContent())
  const uncovered = Number(await summarySection.locator('dd').nth(2).textContent())
  expect(covered + uncovered).toBe(eligible)
  expect(covered).toBe(2) // offerings 1 and 2, deduped from three completed records
  await expect(page.locator('section[aria-label="已覆盖开课"]')).toContainText('演示开课数据结构甲班')
  await expect(page.locator('section[aria-label="已覆盖开课"]')).toContainText('演示开课算法设计')
  // The non-completed record (offering 3) stays uncovered.
  await expect(page.locator('section[aria-label="未覆盖开课"]')).toContainText('演示实践环节未映射课程')
  // Empty term (DEMO-2026-SPRING has no offerings) → zero denominator UI state.
  await page.getByLabel('学期').selectOption({ label: '2026春演示学期（DEMO-2026-SPRING）' })
  await page.getByRole('button', { name: '查询覆盖率' }).click()
  await expect(page.getByText('筛选范围内没有开课')).toBeVisible()
  await logout(page)
})

test('unauthorized coverage access stays fail-closed', async ({ page }) => {
  await page.goto('/supervision/coverage')
  await expect(page).toHaveURL(/\/login(\?|$)/)
  await login(page, 'demo-teacher', DEMO_PASSWORD)
  // The teacher's own dataset is covered by COURSE_READ; the foreign offering detail stays hidden.
  await page.goto('/offerings/detail/2')
  await expect(page.getByText('该开课不存在或当前账号不可见')).toBeVisible()
  await logout(page)
})
