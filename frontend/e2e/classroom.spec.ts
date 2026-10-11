import { expect, test, type Page } from '@playwright/test'

const fixture = process.env.E2E_CLASSROOM_MEDIA_FIXTURE
const demoPassword = process.env.E2E_DEMO_PASSWORD

test.skip(!fixture, 'Pass ClassroomMediaFixture and FfprobePath to Run-BrowserE2E.ps1')

async function login(page: Page, username: string): Promise<void> {
  if (!demoPassword) throw new Error('E2E_DEMO_PASSWORD is required')
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(demoPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/offerings$/)
}

test('synthetic classroom video uploads, streams and seeks through the real browser', async ({ page }) => {
  await login(page, 'demo-supervisor')
  await page.goto('/offerings/detail/2')
  await expect(page.getByRole('heading', { name: '课堂视频与AI分析' })).toBeVisible()
  await page.getByLabel('实际课堂日期').fill('2026-09-14')
  await page.locator('#classroom-schedule').selectOption({ index: 1 })
  await page.locator('#classroom-video').setInputFiles(fixture!)
  await expect(page.getByText('已读取时长：20 秒')).toBeVisible()
  await page.getByLabel('课堂标题（可选）').fill('浏览器合成媒体回归')
  await page.getByRole('button', { name: '上传并开始分析' }).click()
  await expect(page).toHaveURL(/\/classrooms\/\d+$/)

  const video = page.locator('video.classroom-video')
  await expect(video).toBeVisible()
  await expect.poll(() => video.evaluate(element => (element as HTMLVideoElement).duration)).toBeGreaterThan(19)
  await expect.poll(() => video.evaluate(element => (element as HTMLVideoElement).duration)).toBeLessThan(21)
  await expect(page.getByText(/当前全部转写、抬头率和分析均为模拟数据/)).toBeVisible()

  const source = await video.getAttribute('src')
  expect(source).toMatch(/^\/api\/v1\/class-sessions\/media\/\d+\/content$/)
  const range = await page.request.get(source!, { headers: { Range: 'bytes=4-7' } })
  expect(range.status()).toBe(206)
  expect(range.headers()['content-range']).toMatch(/^bytes 4-7\/\d+$/)
  expect(await range.body()).toEqual(Buffer.from('ftyp'))

  await page.locator('.timeline-window').nth(2).click()
  await expect.poll(() => video.evaluate(element => (element as HTMLVideoElement).currentTime)).toBeGreaterThan(9)
  await video.evaluate(element => (element as HTMLVideoElement).pause())
  expect(await video.evaluate(element => (element as HTMLVideoElement).currentTime)).toBeLessThan(13)
})

test('global administrator can see classroom upload controls', async ({ page }) => {
  await login(page, 'demo-admin')
  await page.goto('/offerings/detail/2')
  await expect(page.getByRole('heading', { name: '课堂视频与AI分析' })).toBeVisible()
  await expect(page.locator('#classroom-video')).toBeVisible()
  await expect(page.getByRole('button', { name: '上传并开始分析' })).toBeVisible()
})
