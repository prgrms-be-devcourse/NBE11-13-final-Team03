// 시드가 만든 데이터 풀 (perf/data/*.csv). 쉼표·따옴표가 없는 단순 CSV만 다룬다.
import { SharedArray } from 'k6/data';
import { DATA_DIR } from './config.js';

function readCsv(name) {
  const lines = open(`${DATA_DIR}/${name}`).split(/\r?\n/).filter((l) => l.trim() !== '');
  const header = lines.shift().split(',');
  return lines.map((line) => {
    const cols = line.split(',');
    const row = {};
    header.forEach((h, i) => { row[h] = cols[i]; });
    return row;
  });
}

export const users = new SharedArray('users', () => readCsv('users.csv'));
export const admins = new SharedArray('admins', () => readCsv('admin.csv'));
export const opportunities = new SharedArray('opportunities', () => readCsv('opportunities.csv'));
export const keywords = new SharedArray('keywords', () => readCsv('keywords.csv').map((r) => r.keyword));
export const regions = new SharedArray('regions', () => readCsv('regions.csv'));
export const categories = new SharedArray('categories', () => readCsv('categories.csv'));

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function pickUser() {
  return pick(users);
}

export function pickOpportunityId(type) {
  for (let i = 0; i < 5; i++) {
    const o = pick(opportunities);
    if (!type || o.type === type) return o.id;
  }
  return pick(opportunities).id;
}
