import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { getRegions, parseRegions, regionsQuery, toRegionOptions } from '../src/api/regions.ts'
import { buildRegionTree, chooseRegionParent, chooseRegionChild, chooseRegionLevel, isSelectableRegion, regionParent, regionPath, regionSelectionError } from '../src/pages/profile/regionSelection.ts'
import { validateProfileRegions, profilePatch } from '../src/pages/profile/profileModel.ts'
import { profile, regionRows } from './profile-fixtures.mjs'
const options = toRegionOptions(regionRows)
const source = { status: 'ready', options }
const tree = buildRegionTree(options)

test('region DTO row adapter preserves string codes and names separately from the response wrapper', () => {
  const [option] = toRegionOptions([{ code: '0100000000', name: '테스트 지역', parentCode: null, leaf: true }])
  assert.deepEqual(option, { value: '0100000000', label: '테스트 지역', parentValue: null, isLeaf: true })
  assert.equal(typeof option.value, 'string')
  assert.equal(tree.valid, true)
  assert.equal(tree.roots.length, 3)
})
test('residence requires a leaf, resolves the real parent, and does not commit an intermediate province', () => {
  const first = chooseRegionParent(tree, '1100000000', 'residence')
  assert.deepEqual(first, { parentValue: '1100000000', value: null })
  assert.equal(isSelectableRegion(tree, '1100000000', 'residence'), false)
  assert.equal(chooseRegionChild(tree, first.parentValue, '1168000000', 'residence'), '1168000000')
  assert.equal(isSelectableRegion(tree, '1168000000', 'residence'), true)
  assert.equal(regionParent(tree, '1168000000'), '1100000000')
})
test('top-level leaves including Sejong commit immediately without a hardcoded special-case child', () => {
  assert.deepEqual(chooseRegionParent(tree, '3611000000', 'residence'), { parentValue: '3611000000', value: '3611000000' })
  const arbitraryLeaf = buildRegionTree([{ value: '0100000000', label: '테스트 단일 지역', parentValue: null, isLeaf: true }])
  assert.equal(chooseRegionParent(arbitraryLeaf, '0100000000', 'residence').value, '0100000000')
  assert.equal(chooseRegionChild(tree, '3611000000', '1168000000', 'residence'), null)
})
test('school accepts a non-leaf province, allows district choice and returns to province when district is cleared', () => {
  assert.equal(chooseRegionParent(tree, '1100000000', 'school').value, '1100000000')
  assert.equal(isSelectableRegion(tree, '1100000000', 'school'), true)
  assert.equal(chooseRegionChild(tree, '1100000000', '1168000000', 'school'), '1168000000')
  assert.equal(chooseRegionChild(tree, '1100000000', '', 'school'), '1100000000')
  assert.equal(chooseRegionParent(tree, '', 'school').value, null)
})
test('changing the parent clears the previous district and rejects a child belonging to another province', () => {
  assert.equal(chooseRegionChild(tree, '1100000000', '1168000000', 'residence'), '1168000000')
  assert.deepEqual(chooseRegionParent(tree, '4100000000', 'residence'), { parentValue: '4100000000', value: null })
  assert.equal(chooseRegionChild(tree, '4100000000', '1168000000', 'residence'), null)
  assert.equal(chooseRegionChild(tree, '4100000000', '4136000000', 'residence'), '4136000000')
  assert.equal(chooseRegionParent(tree, '4100000000', 'school').value, '4100000000')
})
test('duplicates, orphan parents, cycles, self-links and contradictory leaf flags fail closed', () => {
  const root = options[0], child = options[1]
  for (const rows of [
    [root, child, { ...child }], [root, { ...child, parentValue: 'missing' }],
    [{ ...root, parentValue: child.value }, child], [{ ...root, parentValue: root.value }],
    [{ ...root, isLeaf: true }, child], [root], [root, { ...child, isLeaf: false }],
    [null], [{ ...root, value: 1100000000 }], [{ ...root, label: '' }], [{ ...root, isLeaf: 'true' }],
  ]) {
    const invalid = buildRegionTree(rows)
    assert.equal(invalid.valid, false)
    assert.equal(isSelectableRegion(invalid, child.value, 'school'), false)
    assert.equal(chooseRegionParent(invalid, root.value, 'school').value, null)
  }
  assert.deepEqual(buildRegionTree([]).roots, [])
})
const originalAdapter = apiClient.defaults.adapter
afterEach(() => { apiClient.defaults.adapter = originalAdapter })
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
test('public region request validates its wrapper, forwards cancellation and never opts into auth recovery', async () => {
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/regions'); assert.equal(config.baseURL, '/api')
    assert.equal(config.requiresAuth, undefined); assert.equal(config.signal, controller.signal)
    return response(config, { regions: regionRows })
  }
  assert.deepEqual(await getRegions(controller.signal), options)
  assert.deepEqual(parseRegions({ regions: [] }), [])
  for (const value of [[], null, {}, { regions: null }, { regions: [null] }, { regions: [{ ...regionRows[0], code: 11 }] }, { regions: [{ ...regionRows[0], leaf: 'false' }] }, { regions: [{ ...regionRows[0], parentCode: 1 }] }, { regions: [regionRows[0]] }]) assert.throws(() => parseRegions(value))
})
test('region 401/404/5xx, network errors and malformed bodies never become an empty success', async () => {
  let calls = 0
  for (const status of [401, 404, 503]) {
    apiClient.defaults.adapter = async (config) => { calls++; throw new AxiosError('test', 'ERR_BAD_RESPONSE', config, {}, response(config, {}, status)) }
    await assert.rejects(getRegions(), (error) => error.status === status)
  }
  assert.equal(calls, 3)
  apiClient.defaults.adapter = async (config) => { throw new AxiosError('test', 'ERR_NETWORK', config, {}) }
  await assert.rejects(getRegions(), (error) => error.kind === 'network')
  apiClient.defaults.adapter = async (config) => response(config, '<html>error</html>')
  await assert.rejects(getRegions(), (error) => error.kind === 'unknown')
})
test('multiple forms share one in-flight region query and reuse the fresh public cache after remount', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  let finish, calls = 0
  const deferred = new Promise((resolve) => { finish = resolve })
  apiClient.defaults.adapter = async (config) => { calls++; await deferred; return response(config, { regions: regionRows }) }
  const a = new QueryObserver(client, regionsQuery()), b = new QueryObserver(client, regionsQuery())
  const offA = a.subscribe(() => {}), offB = b.subscribe(() => {})
  try {
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(calls, 1); finish()
    await client.fetchQuery(regionsQuery())
    offA(); offB()
    assert.deepEqual(await client.fetchQuery(regionsQuery()), options)
    assert.equal(calls, 1)
  } finally { finish(); offA(); offB(); client.clear() }
})
test('a failed region query can be explicitly retried without treating the error as an empty list', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  apiClient.defaults.adapter = async (config) => { throw new AxiosError('fixture', 'ERR_BAD_RESPONSE', config, {}, response(config, {}, 404)) }
  const observer = new QueryObserver(client, regionsQuery()), off = observer.subscribe(() => {})
  try {
    const failure = await observer.refetch()
    assert.equal(failure.isError, true); assert.equal(failure.data, undefined)
    apiClient.defaults.adapter = async (config) => response(config, { regions: regionRows })
    const recovered = await observer.refetch()
    assert.equal(recovered.isSuccess, true); assert.deepEqual(recovered.data, options)
  } finally { off(); client.clear() }
})
const threeRows = [...regionRows, { code: '4111000000', name: '수원시', parentCode: '4100000000', leaf: false }, { code: '4111100000', name: '장안구', parentCode: '4111000000', leaf: true }]
test('three-level residence selection keeps intermediate drafts, restores the full path and clears descendants on parent changes', () => {
  const tree = buildRegionTree(toRegionOptions(threeRows))
  assert.equal(tree.valid, true)
  const first = chooseRegionLevel(tree, [], 0, '4100000000', 'residence')
  assert.equal(first.value, null)
  const second = chooseRegionLevel(tree, first.path, 1, '4111000000', 'residence')
  assert.equal(second.value, null)
  const third = chooseRegionLevel(tree, second.path, 2, '4111100000', 'residence')
  assert.equal(third.value, '4111100000')
  assert.deepEqual(regionPath(tree, third.value), ['4100000000', '4111000000', '4111100000'])
  assert.equal(chooseRegionChild(tree, '4100000000', '4111000000', 'residence'), null)
  assert.deepEqual(chooseRegionLevel(tree, third.path, 0, '1100000000', 'residence'), { path: ['1100000000'], value: null })
  assert.equal(chooseRegionLevel(tree, second.path, 2, '', 'residence').value, null)
  assert.equal(chooseRegionLevel(tree, second.path, 2, '', 'school').value, '4111000000')
  assert.equal(chooseRegionLevel(tree, first.path, 1, '4111000000', 'school').value, '4111000000')
})
test('unavailable/loading/error/empty/invalid data blocks new registration and changed codes but preserves untouched server codes', () => {
  for (const data of [{ status: 'unavailable' }, { status: 'loading' }, { status: 'error' }, { status: 'ready', options: [] }, { status: 'ready', options: [options[0]] }]) {
    assert.ok(regionSelectionError(data, '1168000000', null, 'residence', true))
    assert.ok(regionSelectionError(data, '1168000000', '4136000000', 'residence', false))
    assert.equal(regionSelectionError(data, '1168000000', '1168000000', 'residence', false), undefined)
    assert.equal(regionSelectionError(data, '1100000000', '1100000000', 'school', false), undefined)
    assert.equal(regionSelectionError(data, null, null, 'school', true), undefined)
  }
})
test('unknown existing code is retained without substituting a known option and cannot be used for a new profile', () => {
  assert.equal(regionParent(tree, 'unknown-existing'), '')
  assert.equal(regionSelectionError(source, 'unknown-existing', 'unknown-existing', 'residence', false), undefined)
  assert.ok(regionSelectionError(source, 'unknown-existing', null, 'residence', true))
  assert.ok(regionSelectionError(source, '1100000000', '1100000000', 'residence', false))
})
test('form region validation separates creation, unchanged PATCH, verified residence change and school province choice', () => {
  const initial = { ...profile, residenceRegionCode: '1168000000', schoolRegionCode: '1100000000' }
  const edited = { ...initial, employmentStatus: 'EMPLOYED' }
  assert.deepEqual(validateProfileRegions(edited, initial, { status: 'unavailable' }), {})
  assert.deepEqual(profilePatch(edited, initial), { employmentStatus: 'EMPLOYED' })
  assert.ok(validateProfileRegions(initial, null, { status: 'unavailable' }).residenceRegionCode)
  assert.deepEqual(validateProfileRegions(initial, null, source), {})
  assert.ok(validateProfileRegions({ ...initial, residenceRegionCode: '1100000000' }, initial, source).residenceRegionCode)
  assert.deepEqual(validateProfileRegions({ ...initial, residenceRegionCode: '3611000000' }, initial, source), {})
  assert.ok(validateProfileRegions({ ...initial, schoolRegionCode: 'unknown' }, initial, source).schoolRegionCode)
})
