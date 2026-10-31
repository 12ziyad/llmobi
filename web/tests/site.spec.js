import { test, expect } from '@playwright/test'
import catalog from './catalog.json' with { type: 'json' }

test.beforeEach(async ({ page }) => {
  // Isolated browser fixtures, never sent to the live API or Intermission.
  await page.route('https://fonts.googleapis.com/**', route => route.abort())
  await page.route('https://llmobi-api.gpmai.workers.dev/**', route => {
    const url = route.request().url()
    return route.fulfill({ json: url.endsWith('/v1/catalog') ? catalog : { release: null }, headers: { 'access-control-allow-origin': '*' } })
  })
})

for (const viewport of [{ width: 390, height: 844 }, { width: 1365, height: 900 }]) {
  test(`download journey and layout at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport)
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    await page.goto('/')
    await expect(page.locator('.mh span')).toHaveText('20 models · live')
    await expect(page.locator('.row')).toHaveCount(20)
    await expect(page.getByRole('link', { name: 'Download for Android', exact: true })).toHaveAttribute('href', 'https://llmobi-api.gpmai.workers.dev/dl')
    await expect(page.getByRole('link', { name: 'Use the direct APK link' })).toHaveAttribute('href', '/llmobi.apk')
    await page.getByRole('button', { name: 'How it works' }).click()
    await expect(page.locator('.how-steps li')).toHaveCount(5)
    await expect(page.locator('.how-btn')).toHaveAttribute('aria-expanded', 'true')
    await page.getByRole('button', { name: 'How it works' }).click()
    await expect(page.locator('.how-steps')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    expect(errors).toEqual([])
    await page.screenshot({ path: `test-results/llmobi-${viewport.width}.png`, fullPage: true })
  })
}

test('API failure retains models and a direct download', async ({ page }) => {
  await page.route('**/v1/catalog', route => route.abort())
  await page.goto('/')
  await expect(page.locator('.mh span')).toHaveText('offline')
  await expect(page.locator('.row')).toHaveCount(4)
  await expect(page.getByRole('link', { name: 'Use the direct APK link' })).toBeVisible()
})

test('empty catalog does not remain stuck loading', async ({ page }) => {
  await page.route('**/v1/catalog', route => route.fulfill({ json: { models: [] } }))
  await page.goto('/')
  await expect(page.locator('.mh span')).toHaveText('offline')
})

test('release metadata updates the APK version and size', async ({ page }) => {
  await page.route('**/v1/release', route => route.fulfill({ json: { release: { versionName: '1.1.0', bytes: 8388608 } } }))
  await page.goto('/')
  await expect(page.locator('.fine')).toContainText('8.0 MB · v1.1.0')
})
