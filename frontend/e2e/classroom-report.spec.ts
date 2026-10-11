import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/class-sessions/media/*/generated-report/versions*', route =>
    route.fulfill({ json: { items: [], nextPage: null } }))
})

test('changing classroom routes clears the previous AI report and keeps evidence visible', async ({ page }) => {
  await page.clock.install()
  let secondClassroomReads = 0
  let pendingReportReads = 0
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: {
    userId: 3, username: 'teacher-a-test', teacherId: 1, roles: ['TEACHER'],
    authorizationScopes: [{ roleCode: 'TEACHER', scopeType: 'SELF_TEACHER',
      collegeId: null, teacherId: 1, offeringId: null }],
  } }))
  await page.route('**/api/v1/class-sessions/media/*/content', route => route.fulfill({ status: 404 }))
  await page.route('**/api/v1/class-sessions/media/11/generated-report/versions?page=0', route => route.fulfill({ json: {
    items: [{ reportId: 17, generatedAt: '2026-09-24T10:00:00', current: true },
      { reportId: 18, generatedAt: '2026-09-23T10:00:00', current: false }], nextPage: null,
  } }))
  await page.route('**/api/v1/class-sessions/media/22/generated-report/versions?page=0', route => route.fulfill({ json: {
    items: [], nextPage: null,
  } }))
  await page.route('**/api/v1/class-sessions/*', route => {
    const second = route.request().url().endsWith('/202')
    if (second) secondClassroomReads++
    const mediaId = second ? 22 : 11
    return route.fulfill({ json: {
      classSessionId: second ? 202 : 101, offeringId: 101, scheduleId: 1001,
      sessionDate: '2026-09-14', title: second ? '课堂 B' : '课堂 A', createdAt: '2026-09-14T00:00:00',
      media: [{ mediaId, originalFilename: 'test.mp4', contentType: 'video/mp4', byteSize: 1024,
        sha256: 'a'.repeat(64), durationMs: 60_000, status: 'READY',
        playbackUrl: `/api/v1/class-sessions/media/${mediaId}/content`, moduleJobs: [],
        report: { reportId: 1, status: 'READY', dataMode: 'EXTERNAL', meanHeadUpRate: 0.75,
          validCoverageRate: 0.5, estimatedAttendanceCount: 42, attendanceObservedWindows: 8,
          attendanceConfidence: 'HIGH', summary: null, analysis: null, evidenceStartMs: null,
          evidenceEndMs: null, errorMessage: null,
          transcript: [{ id: 'seg-1', startMs: 1000, endMs: 5000, text: '测试转写' }],
          headUpWindows: [{ id: 'win-1', startMs: 0, endMs: 10_000, quality: 'VALID',
            qualityReason: null, headUpCount: 3, validObservedCount: 4, headUpRate: 0.75 }] } }],
    } })
  })
  await page.route('**/api/v1/class-sessions/media/11/generated-report', route => route.fulfill({ json: {
    reportVersion: '1.0', topics: [{ startMs: 1000, endMs: 5000, summary: '只属于课堂 A 的主题',
      evidenceRefs: [{ resultId: 1, itemType: 'SEGMENT', itemId: 'seg-1' }] }], changes: [],
    interpretations: [{ hypothesis: '只属于课堂 A 的解释', alternatives: ['其他可能'], suggestion: '核对原始片段',
      evidenceRefs: [{ resultId: 1, itemType: 'SEGMENT', itemId: 'seg-1' },
        { resultId: 2, itemType: 'WINDOW', itemId: 'win-1' }] }],
    limitations: ['不能推断理解程度'],
  } }))
  await page.route('**/api/v1/class-sessions/media/22/generated-report', route => {
    pendingReportReads++
    return route.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/class-sessions/media/22/generated-report/progress', route => route.fulfill({ json: {
    state: 'QUEUED',
  } }))

  await page.goto('/classrooms/101')
  await expect(page.getByRole('heading', { name: '课堂 A', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '本次课综合结论' })).toBeVisible()
  for (const heading of ['匿名人数观测', '内容进程与同期观测', '课堂前、中、后段观测',
    '建议结合视频与转写核对', '结论边界'])
    await expect(page.getByRole('heading', { name: heading })).toBeVisible()
  await expect(page.getByText(/同期有效窗口抬头率 75.00%/)).toBeVisible()
  await expect(page.getByText(/当前应到人数尚未提供，因此不计算出勤率/)).toBeVisible()
  await expect(page.getByText(/开场阶段 · 0:00–0:20/)).toBeVisible()
  await expect(page.getByText(/有效覆盖 50.00% · 1 个窗口/)).toBeVisible()
  await expect(page.getByText('最低有效窗口')).toBeVisible()
  await expect(page.getByText('最高有效窗口')).toBeVisible()
  await expect(page.getByText('识别出勤人数（视频估计）').locator('..').getByText('42 人')).toBeVisible()
  await expect(page.getByText('应到人数', { exact: true }).locator('..').getByText('—', { exact: true })).toBeVisible()
  const back = page.getByRole('link', { name: '返回开课详情' })
  await expect(back).toHaveAttribute('href', '/offerings/detail/101')
  expect((await back.boundingBox())!.y).toBeLessThan((await page.getByRole('heading', { name: '课堂 A', exact: true }).boundingBox())!.y)
  const toggle = page.getByRole('button', { name: '展开转写（1 段）' })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('#classroom-transcript-list')).toBeHidden()
  await toggle.click()
  await expect(page.getByRole('button', { name: '收起转写' })).toHaveAttribute('aria-expanded', 'true')
  await expect(page.locator('#classroom-transcript-list')).toBeVisible()
  await page.getByRole('button', { name: '收起转写' }).click()
  await expect(page.locator('#classroom-transcript-list')).toBeHidden()
  await expect(page.locator('.analysis-topic-list > li').first()).toContainText('只属于课堂 A 的主题')
  await expect(page.getByText('版本记录 #17')).toBeVisible()
  await expect(page.getByText('版本记录 #18')).toBeVisible()
  await expect(page.getByRole('button', { name: /版本记录 #18/ })).toHaveCount(0)
  await expect(page.getByText('暂无独立文字摘要；客观统计和时间窗口见上方。')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '证据 win-1' })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看证据' })).toHaveCount(0)

  await page.evaluate(async () => {
    const app = (document.querySelector('#app') as Element & { __vue_app__?: {
      config: { globalProperties: { $router: { push: (path: string) => Promise<unknown> } } }
    } }).__vue_app__
    if (!app) throw new Error('Vue app was not mounted')
    await app.config.globalProperties.$router.push('/classrooms/202')
  })
  await expect(page.getByRole('heading', { name: '课堂 B', exact: true })).toBeVisible()
  await expect(page.locator('.analysis-topic-list > li').filter({ hasText: '只属于课堂 A 的主题' })).toHaveCount(0)
  await expect(page.getByText('只属于课堂 A 的解释')).toHaveCount(0)
  await expect(page.getByText('版本记录 #17')).toHaveCount(0)
  await expect(page.getByText('报告正在排队生成。')).toBeVisible()
  expect(secondClassroomReads).toBe(1)
  expect(pendingReportReads).toBe(1)
  await page.clock.runFor(5_000)
  await expect.poll(() => pendingReportReads).toBe(2)
  expect(secondClassroomReads).toBe(1)
})

for (const state of ['PAUSED', 'NEEDS_REVIEW'] as const) {
  test(`${state} stops automatic report polling but manual refresh can display a later report`, async ({ page }) => {
    await page.clock.install()
    await page.route('**/api/v1/auth/me', route => route.fulfill({ json: {
      userId: 3, username: 'teacher-a-test', teacherId: 1, roles: ['TEACHER'],
      authorizationScopes: [{ roleCode: 'TEACHER', scopeType: 'SELF_TEACHER',
        collegeId: null, teacherId: 1, offeringId: null }],
    } }))
    await page.route('**/api/v1/class-sessions/media/*/content', route => route.fulfill({ status: 404 }))
    await page.route('**/api/v1/class-sessions/303', route => route.fulfill({ json: {
      classSessionId: 303, offeringId: 101, scheduleId: 1001,
      sessionDate: '2026-09-14', title: '课堂 C', createdAt: '2026-09-14T00:00:00',
      media: [{ mediaId: 33, originalFilename: 'test.mp4', contentType: 'video/mp4', byteSize: 1024,
        sha256: 'a'.repeat(64), durationMs: 60_000, status: 'READY',
        playbackUrl: '/api/v1/class-sessions/media/33/content', moduleJobs: [],
        report: { reportId: 1, status: 'READY', dataMode: 'EXTERNAL', meanHeadUpRate: 0.75,
          validCoverageRate: 0.5, summary: null, analysis: null, evidenceStartMs: null,
          evidenceEndMs: null, errorMessage: null, transcript: [], headUpWindows: [] } }],
    } }))
    let reportReads = 0
    let reportAvailable = false
    await page.route('**/api/v1/class-sessions/media/33/generated-report', route => {
      reportReads++
      return reportAvailable
        ? route.fulfill({ json: { reportVersion: '1.0', topics: [{ startMs: 0, endMs: 1000,
          summary: '后来生成的报告', evidenceRefs: [] }], changes: [], interpretations: [],
          limitations: ['不能推断理解程度'] } })
        : route.fulfill({ status: 204 })
    })
    await page.route('**/api/v1/class-sessions/media/33/generated-report/progress', route =>
      route.fulfill({ json: { state } }))

    await page.goto('/classrooms/303')
    await expect(page.getByRole('heading', { name: '课堂 C', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '刷新报告' })).toBeVisible()
    await expect(page.getByRole('button', { name: '再次生成AI报告' })).toHaveCount(0)
    expect(reportReads).toBe(1)
    await page.clock.runFor(15_000)
    expect(reportReads).toBe(1)

    reportAvailable = true
    await page.getByRole('button', { name: '刷新报告' }).click()
    await expect(page.locator('.analysis-topic-list > li').first()).toContainText('后来生成的报告')
    expect(reportReads).toBe(2)
  })
}

test('only the course supervisor can explicitly queue a possibly paid new report attempt', async ({ page }) => {
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: {
    userId: 5, username: 'supervisor-a-test', teacherId: null, roles: ['SUPERVISOR'],
    authorizationScopes: [{ roleCode: 'SUPERVISOR', scopeType: 'OFFERING',
      collegeId: null, teacherId: null, offeringId: 101 }],
  } }))
  await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: {
    token: 'csrf-test', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }))
  await page.route('**/api/v1/class-sessions/media/44/content', route => route.fulfill({ status: 404 }))
  await page.route('**/api/v1/class-sessions/404', route => route.fulfill({ json: {
    classSessionId: 404, offeringId: 101, scheduleId: 1001,
    sessionDate: '2026-09-14', title: '督导课堂', createdAt: '2026-09-14T00:00:00',
    media: [{ mediaId: 44, originalFilename: 'test.mp4', contentType: 'video/mp4', byteSize: 1024,
      sha256: 'a'.repeat(64), durationMs: 60_000, status: 'READY',
      playbackUrl: '/api/v1/class-sessions/media/44/content', moduleJobs: [],
      report: { reportId: 1, status: 'READY', dataMode: 'EXTERNAL', meanHeadUpRate: 0.75,
        validCoverageRate: 0.5, summary: null, analysis: null, evidenceStartMs: null,
        evidenceEndMs: null, errorMessage: null, transcript: [], headUpWindows: [] } }],
  } }))
  await page.route('**/api/v1/class-sessions/media/44/generated-report', route =>
    route.fulfill({ status: 204 }))
  let state = 'NEEDS_REVIEW'
  let submissions = 0
  await page.route('**/api/v1/class-sessions/media/44/generated-report/progress', route =>
    route.fulfill({ json: { state } }))
  await page.route('**/api/v1/class-sessions/media/44/generated-report/retry', route => {
    submissions++
    expect(route.request().method()).toBe('POST')
    expect(route.request().postDataJSON()).toEqual({ confirmAdditionalCharge: true })
    expect(route.request().headers()['idempotency-key']).toBeTruthy()
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-test')
    state = 'QUEUED'
    return route.fulfill({ status: 202, json: { state, replayed: false } })
  })

  await page.goto('/classrooms/404')
  const button = page.getByRole('button', { name: '再次生成AI报告' })
  await expect(button).toBeVisible()
  page.once('dialog', dialog => void dialog.dismiss())
  await button.click()
  expect(submissions).toBe(0)
  page.once('dialog', dialog => void dialog.accept())
  await button.click()
  await expect(page.getByText('报告正在排队生成。')).toBeVisible()
  expect(submissions).toBe(1)
})

test('an uncertain paid retry reuses its operation key after a page reload', async ({ page }) => {
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: {
    userId: 5, username: 'supervisor-a-test', teacherId: null, roles: ['SUPERVISOR'],
    authorizationScopes: [{ roleCode: 'SUPERVISOR', scopeType: 'OFFERING',
      collegeId: null, teacherId: null, offeringId: 101 }],
  } }))
  await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: {
    token: 'csrf-test', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }))
  await page.route('**/api/v1/class-sessions/media/44/content', route => route.fulfill({ status: 404 }))
  await page.route('**/api/v1/class-sessions/404', route => route.fulfill({ json: {
    classSessionId: 404, offeringId: 101, scheduleId: 1001,
    sessionDate: '2026-09-14', title: '督导课堂', createdAt: '2026-09-14T00:00:00',
    media: [{ mediaId: 44, originalFilename: 'test.mp4', contentType: 'video/mp4', byteSize: 1024,
      sha256: 'a'.repeat(64), durationMs: 60_000, status: 'READY',
      playbackUrl: '/api/v1/class-sessions/media/44/content', moduleJobs: [],
      report: { reportId: 1, status: 'READY', dataMode: 'EXTERNAL', meanHeadUpRate: 0.75,
        validCoverageRate: 0.5, summary: null, analysis: null, evidenceStartMs: null,
        evidenceEndMs: null, errorMessage: null, transcript: [], headUpWindows: [] } }],
  } }))
  await page.route('**/api/v1/class-sessions/media/44/generated-report', route =>
    route.fulfill({ status: 204 }))
  let state = 'NEEDS_REVIEW'
  await page.route('**/api/v1/class-sessions/media/44/generated-report/progress', route =>
    route.fulfill({ json: { state } }))
  const keys: string[] = []
  await page.route('**/api/v1/class-sessions/media/44/generated-report/retry', route => {
    keys.push(route.request().headers()['idempotency-key'])
    if (keys.length === 1) return route.abort('failed')
    state = 'QUEUED'
    return route.fulfill({ status: 200, json: { state, replayed: true } })
  })

  await page.goto('/classrooms/404')
  const button = page.getByRole('button', { name: '再次生成AI报告' })
  await expect(button).toBeVisible()
  page.once('dialog', dialog => void dialog.accept())
  await button.click()
  await expect(page.getByRole('alert')).toContainText('无法连接服务')
  await page.reload()
  await expect(button).toBeVisible()
  page.once('dialog', dialog => void dialog.accept())
  await button.click()
  await expect(page.getByText('报告正在排队生成。')).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
})

test('an uncertain paid module retry reuses its operation key after a page reload', async ({ page }) => {
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: {
    userId: 5, username: 'supervisor-a-test', teacherId: null, roles: ['SUPERVISOR'],
    authorizationScopes: [{ roleCode: 'SUPERVISOR', scopeType: 'OFFERING',
      collegeId: null, teacherId: null, offeringId: 101 }],
  } }))
  await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: {
    token: 'csrf-test', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }))
  await page.route('**/api/v1/class-sessions/media/55/content', route => route.fulfill({ status: 404 }))
  await page.route('**/api/v1/class-sessions/505', route => route.fulfill({ json: {
    classSessionId: 505, offeringId: 101, scheduleId: 1001,
    sessionDate: '2026-09-14', title: '模块重试课堂', createdAt: '2026-09-14T00:00:00',
    media: [{ mediaId: 55, originalFilename: 'test.mp4', contentType: 'video/mp4', byteSize: 1024,
      sha256: 'a'.repeat(64), durationMs: 60_000, status: 'READY',
      playbackUrl: '/api/v1/class-sessions/media/55/content',
      moduleJobs: [{ jobId: 1, moduleType: 'TRANSCRIPTION', status: 'SUCCEEDED', providerId: 'asr',
        moduleVersion: '1', resultRevision: 1, errorCode: null, errorMessage: null },
      { jobId: 2, moduleType: 'HEAD_UP', status: 'FAILED', providerId: 'vision', moduleVersion: '1',
        resultRevision: 1, errorCode: 'MODULE_FAILED', errorMessage: '模块失败' }],
      report: { reportId: 1, status: 'FAILED', dataMode: 'EXTERNAL', meanHeadUpRate: null,
        validCoverageRate: null, estimatedAttendanceCount: null, attendanceObservedWindows: 0,
        attendanceConfidence: null, summary: null, analysis: null, evidenceStartMs: null,
        evidenceEndMs: null, errorMessage: '模块失败', transcript: [], headUpWindows: [] } }],
  } }))
  await page.route('**/api/v1/class-sessions/media/55/generated-report', route => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/class-sessions/media/55/generated-report/progress', route =>
    route.fulfill({ json: { state: 'FAILED_INPUT' } }))
  const keys: string[] = []
  await page.route('**/api/v1/class-sessions/media/55/module-jobs/retry', route => {
    keys.push(route.request().headers()['idempotency-key'])
    expect(route.request().postDataJSON()).toEqual({ confirmAdditionalCharge: true })
    if (keys.length === 1) return route.abort('failed')
    return route.fulfill({ status: 200, json: { state: 'WAITING_INPUT', replayed: true } })
  })

  await page.goto('/classrooms/505')
  const button = page.getByRole('button', { name: '重新分析失败任务' })
  page.once('dialog', dialog => void dialog.accept())
  await button.click()
  await expect(page.getByText('无法连接服务，请检查网络。')).toBeVisible()
  await page.reload()
  page.once('dialog', dialog => void dialog.accept())
  await button.click()
  await expect.poll(() => keys.length).toBe(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
})

test('the course supervisor can recover an existing transcription from the classroom page', async ({ page }) => {
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: {
    userId: 5, username: 'supervisor-a-test', teacherId: null, roles: ['SUPERVISOR'],
    authorizationScopes: [{ roleCode: 'SUPERVISOR', scopeType: 'OFFERING',
      collegeId: null, teacherId: null, offeringId: 101 }],
  } }))
  await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: {
    token: 'csrf-test', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }))
  let recovered = false
  await page.route('**/api/v1/class-sessions/606', route => route.fulfill({ json: {
    classSessionId: 606, offeringId: 101, scheduleId: 1001,
    sessionDate: '2026-09-14', title: '转写恢复课堂', createdAt: '2026-09-14T00:00:00',
    media: [{ mediaId: 66, originalFilename: 'test.mp4', contentType: 'video/mp4', byteSize: 1024,
      sha256: 'a'.repeat(64), durationMs: 60_000, status: 'READY', playbackUrl: '/missing.mp4',
      moduleJobs: recovered ? [] : [
        { jobId: 1, moduleType: 'TRANSCRIPTION', status: 'FAILED', providerId: 'asr', moduleVersion: '1',
          resultRevision: null, errorCode: 'MODULE_INTEGRATION_FAILED', errorMessage: '本地保存失败' },
        { jobId: 2, moduleType: 'HEAD_UP', status: 'SUCCEEDED', providerId: 'vision', moduleVersion: '1',
          resultRevision: 1, errorCode: null, errorMessage: null }],
      report: { reportId: 1, status: recovered ? 'READY' : 'FAILED', dataMode: 'EXTERNAL',
        meanHeadUpRate: null, validCoverageRate: null, estimatedAttendanceCount: null,
        attendanceObservedWindows: 0, attendanceConfidence: null, summary: null, analysis: null,
        evidenceStartMs: null, evidenceEndMs: null, errorMessage: recovered ? null : '本地保存失败',
        transcript: [], headUpWindows: [] } }],
  } }))
  await page.route('**/api/v1/class-sessions/media/66/generated-report', route => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/class-sessions/media/66/generated-report/progress', route =>
    route.fulfill({ json: { state: recovered ? 'QUEUED' : 'FAILED_INPUT' } }))
  let submissions = 0
  await page.route('**/api/v1/class-sessions/media/66/transcription-result/recover', route => {
    submissions++
    expect(route.request().postDataJSON()).toEqual({ confirmExistingResult: true })
    expect(route.request().headers()['idempotency-key']).toBeTruthy()
    recovered = true
    return route.fulfill({ status: 202, json: { state: 'READY', replayed: false } })
  })

  await page.goto('/classrooms/606')
  const button = page.getByRole('button', { name: '恢复已有转写结果' })
  await expect(button).toBeVisible()
  await expect(page.getByText(/不会提交新的供应商请求/)).toBeVisible()
  await button.click()
  await expect.poll(() => submissions).toBe(1)
})
