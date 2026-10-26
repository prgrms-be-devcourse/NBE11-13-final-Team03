import assert from 'node:assert/strict'
import { test } from 'node:test'
import { toRegionOptions } from '../src/api/regions.ts'
import { buildRegionTree, chooseRegionParent, chooseRegionChild, isSelectableRegion, regionParent, regionSelectionError } from '../src/pages/profile/regionSelection.ts'
import { validateProfileRegions, profilePatch } from '../src/pages/profile/profileModel.ts'
import { profile, regionRows } from './profile-fixtures.mjs'
const options = toRegionOptions(regionRows)
const source = { status: 'ready', options }
const tree = buildRegionTree(options)

test('provisional DTO row adapter preserves string codes and names without defining an HTTP path or wrapper', () => {
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
test('duplicates, orphan parents, cycles, self-links, excessive depth and contradictory leaf flags fail closed', () => {
  const root = options[0], child = options[1]
  for (const rows of [
    [root, child, { ...child }], [root, { ...child, parentValue: 'missing' }],
    [{ ...root, parentValue: child.value }, child], [{ ...root, parentValue: root.value }],
    [root, { ...child, isLeaf: false }, { ...child, value: 'deeper', parentValue: child.value }],
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
